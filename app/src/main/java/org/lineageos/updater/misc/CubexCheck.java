/*
 * Copyright (C) 2026 The CubeOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.updater.misc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import android.util.Log;

import org.cubeapp.cubexlight.Bytes;
import org.cubeapp.cubexlight.CubeOSRegistry;
import org.cubeapp.cubexlight.RegistryResult;
import org.cubeapp.cubexlight.RegistryVerifier;
import org.lineageos.updater.R;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * CubeOS: checks a downloaded OTA against the CubeOSRegistry contract on CUBEX
 * (CubeOSApps/docs/CUBEOS-REGISTRY.md) through the CUBEX light client, after the package's
 * signature has passed RecoverySystem.verifyPackage. Both checks must pass before anything
 * installs; this adds a check and never replaces one.
 *
 * <ul>
 *   <li>Published, hash matches, not revoked: {@link Verdict#VERIFIED}.</li>
 *   <li>Revoked, hash mismatch, no record, or a proof that fails to verify:
 *       {@link Verdict#REFUSED}. Never overridable.</li>
 *   <li>CUBEX can't be reached, the light client can't verify a fresh header, or the registry
 *       isn't deployed yet: {@link Verdict#WAITING}. The download is kept and checked again;
 *       after {@link #OVERRIDE_AFTER_MS} the person may install it anyway behind a warning
 *       ({@link Verdict#OVERRIDDEN}), never automatic updates.</li>
 *   <li>The registry address isn't pinned in this build yet (before deployment):
 *       {@link Verdict#NOT_DEPLOYED}. Nothing can be checked, so the update is shown as
 *       "Can't verify on CUBEX yet" and the existing checks (package signature, update_engine's
 *       payload verification) decide alone, exactly as before the registry existed. Once
 *       {@link CubeOSRegistry#ADDRESS} is set this verdict never occurs.</li>
 * </ul>
 *
 * Verdicts are stored per file (path, size and modification time), so a file that changes after
 * its check is not cleared. Blocking calls go off the main thread.
 */
public final class CubexCheck {
    private static final String TAG = "CubexCheck";
    private static final String PREFS = "cubex_registry";
    private static final String METADATA_PATH = "META-INF/com/android/metadata";

    /** How long CUBEX must stay unreachable before "Install Anyway" is offered. */
    public static final long OVERRIDE_AFTER_MS = 7L * 24 * 60 * 60 * 1000;

    public enum Verdict { VERIFIED, OVERRIDDEN, NOT_DEPLOYED, WAITING, REFUSED }

    /** A verdict and the words the update card shows for it. */
    public static final class Outcome {
        public final Verdict verdict;
        public final int message;
        public final String detail;

        Outcome(Verdict verdict, int message, String detail) {
            this.verdict = verdict;
            this.message = message;
            this.detail = detail;
        }
    }

    private static volatile RegistryVerifier sVerifier;

    private CubexCheck() {
    }

    /** Whether the registry address is pinned in this build (it is empty until deployment). */
    public static boolean isDeployed() {
        return !CubeOSRegistry.ADDRESS.isEmpty();
    }

    private static RegistryVerifier verifier(Context context) {
        RegistryVerifier v = sVerifier;
        if (v == null) {
            synchronized (CubexCheck.class) {
                v = sVerifier;
                if (v == null) {
                    File dir = new File(context.getApplicationContext().getNoBackupFilesDir(), "cubex");
                    v = RegistryVerifier.create(dir);
                    sVerifier = v;
                }
            }
        }
        return v;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String fingerprint(File file) {
        return file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
    }

    /** ro.lineage.device, the name CubeOS releases are recorded under (e.g. ygf_f35). */
    public static String device() {
        String d = SystemProperties.get("ro.lineage.device", "");
        if (d.isEmpty()) d = SystemProperties.get("ro.cube.device", "");
        if (d.isEmpty()) d = SystemProperties.get("ro.product.device", "");
        return d;
    }

    /** The build number (post-build-incremental) and type from the package's signed metadata. */
    private static final class Metadata {
        long build = -1;
        boolean incremental;
    }

    private static Metadata readMetadata(File file) throws IOException {
        Metadata m = new Metadata();
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry e = zip.getEntry(METADATA_PATH);
            if (e == null || e.getSize() > 64 * 1024) {
                return m;
            }
            String text;
            try (InputStream in = zip.getInputStream(e)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String line : text.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if (k.equals("post-build-incremental")) {
                    try {
                        m.build = Long.parseLong(v);
                    } catch (NumberFormatException ignored) {
                        m.build = -1;
                    }
                } else if (k.equals("pre-build-incremental") || k.equals("pre-build")) {
                    m.incremental = !v.isEmpty();
                }
            }
        }
        return m;
    }

    /**
     * Checks [file] on CUBEX and records the verdict. Call only after the package signature
     * passed. With [rehash] false a hash recorded for this exact file earlier is reused (the
     * background re-checks while CUBEX is unreachable); otherwise the file is hashed again.
     */
    public static Outcome check(Context context, File file, boolean rehash) {
        SharedPreferences p = prefs(context);
        String fp = fingerprint(file);
        String shaHex = rehash ? null : p.getString("sha:" + fp, null);
        Outcome o;
        if (!isDeployed()) {
            // Before deployment there is nothing to ask: don't block on this alone.
            o = new Outcome(Verdict.NOT_DEPLOYED, R.string.card_cubex_not_deployed,
                    "registry not deployed");
            record(p, fp, null, o);
            return o;
        }
        try {
            if (shaHex == null) {
                shaHex = Bytes.INSTANCE.hex(CubeOSRegistry.INSTANCE.sha256(file));
                p.edit().putString("sha:" + fp, shaHex).apply();
            }
            Metadata m = readMetadata(file);
            RegistryResult r;
            if (m.build <= 0) {
                // A CubeOS OTA always names its numeric build: without one it can't have a record.
                Log.w(TAG, "No numeric post-build-incremental in " + file.getName());
                o = new Outcome(Verdict.REFUSED, R.string.card_cubex_no_record, "no build number");
            } else {
                r = verifier(context).verifyRelease(device(), m.build,
                        m.incremental ? null : Bytes.INSTANCE.unhex(shaHex));
                Log.i(TAG, "Build " + m.build + (m.incremental ? " (incremental)" : "") + ": "
                        + r.describe());
                o = outcome(r);
            }
        } catch (IOException | RuntimeException e) {
            // The file couldn't be read: it was checked by its signature a moment ago, so this is
            // local trouble, not CUBEX's answer. Wait rather than refuse, and never install.
            Log.e(TAG, "Couldn't check " + file, e);
            o = new Outcome(Verdict.WAITING, R.string.card_cubex_waiting, String.valueOf(e.getMessage()));
        }
        record(p, fp, shaHex, o);
        return o;
    }

    private static Outcome outcome(RegistryResult r) {
        if (r instanceof RegistryResult.Verified) {
            Long newer = ((RegistryResult.Verified) r).getNewerBuild();
            if (newer != null) {
                Log.w(TAG, "A newer CubeOS build (" + newer + ") is recorded on CUBEX than the one offered");
            }
            return new Outcome(Verdict.VERIFIED, R.string.card_cubex_verified, r.describe());
        } else if (r instanceof RegistryResult.Revoked) {
            return new Outcome(Verdict.REFUSED, R.string.card_cubex_revoked, r.describe());
        } else if (r instanceof RegistryResult.Mismatch) {
            return new Outcome(Verdict.REFUSED, R.string.card_cubex_mismatch, r.describe());
        } else if (r instanceof RegistryResult.NoRecord) {
            return new Outcome(Verdict.REFUSED, R.string.card_cubex_no_record, r.describe());
        } else if (r instanceof RegistryResult.BadProof) {
            Log.e(TAG, "CUBEX proof failed verification, treating as an attack: " + r.describe());
            return new Outcome(Verdict.REFUSED, R.string.card_cubex_bad_proof, r.describe());
        }
        return new Outcome(Verdict.WAITING, R.string.card_cubex_waiting, r.describe());
    }

    private static void record(SharedPreferences p, String fp, String shaHex, Outcome o) {
        SharedPreferences.Editor e = p.edit().putString("verdict:" + fp, o.verdict.name());
        if (shaHex != null) {
            String since = "waiting-since:" + shaHex;
            if (o.verdict == Verdict.WAITING) {
                if (!p.contains(since)) e.putLong(since, System.currentTimeMillis());
            } else {
                e.remove(since);
            }
        }
        e.apply();
    }

    private static Verdict verdict(Context context, File file) {
        if (file == null || !file.exists()) return null;
        String v = prefs(context).getString("verdict:" + fingerprint(file), null);
        if (v == null) return null;
        try {
            return Verdict.valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * True only when [file], unchanged, was verified on CUBEX, explicitly installed anyway, or
     * checked while the registry wasn't deployed in this build (a NOT_DEPLOYED verdict stops
     * counting the moment the address is pinned: the file is then checked on CUBEX again).
     */
    public static boolean isCleared(Context context, File file) {
        Verdict v = verdict(context, file);
        return v == Verdict.VERIFIED || v == Verdict.OVERRIDDEN
                || (v == Verdict.NOT_DEPLOYED && !isDeployed());
    }

    /** True when [file] was cleared only because the registry isn't deployed yet. */
    public static boolean isUnverifiedNotDeployed(Context context, File file) {
        return !isDeployed() && verdict(context, file) == Verdict.NOT_DEPLOYED;
    }

    public static boolean isOverridden(Context context, File file) {
        return verdict(context, file) == Verdict.OVERRIDDEN;
    }

    /** True when [file] is waiting for CUBEX (its signature passed, the registry couldn't be asked). */
    public static boolean isWaiting(Context context, File file) {
        return verdict(context, file) == Verdict.WAITING;
    }

    /** True when [file] has waited for CUBEX for at least {@link #OVERRIDE_AFTER_MS}. */
    public static boolean canOverride(Context context, File file) {
        if (!isWaiting(context, file)) return false;
        SharedPreferences p = prefs(context);
        String sha = p.getString("sha:" + fingerprint(file), null);
        if (sha == null) return false;
        long since = p.getLong("waiting-since:" + sha, 0);
        return since > 0 && System.currentTimeMillis() - since >= OVERRIDE_AFTER_MS;
    }

    /**
     * The person chose "Install Anyway" after the warning. Allowed only for a file that has
     * waited long enough; its signature was checked when it was verified.
     */
    public static boolean override(Context context, File file) {
        if (!canOverride(context, file)) return false;
        Log.w(TAG, "Installing " + file.getName() + " without a CUBEX check, on the person's say-so");
        prefs(context).edit().putString("verdict:" + fingerprint(file), Verdict.OVERRIDDEN.name()).apply();
        return true;
    }
}
