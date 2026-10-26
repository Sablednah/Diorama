package com.sablednah.diorama;

import java.io.InputStream;
import java.util.Properties;

/**
 * Which build this is — the commit these bytes came from.
 *
 * <h2>Why a version number is not enough</h2>
 *
 * <p>A version answers "which release". During development that is a different question from
 * "which bytes", and the gap between the two cost three sessions time in a single day: a jar
 * rebuilt under an unchanged version broke an already-shipped consumer, a dependency range admitted
 * a jar it could not run against, and nobody could tell a stale instance jar from its filename.</p>
 *
 * <p><b>The startup log line is the half that matters.</b> A stamp inside a jar says what is on
 * disk. The log line says what actually <em>ran</em> — which is the question a bug report needs
 * answered, and the one none of us could answer afterwards.</p>
 *
 * <h2>Three decisions worth keeping</h2>
 *
 * <ul>
 * <li><b>Read the properties file, not the manifest.</b> A dev run loads from a classes directory:
 *     there is no jar and therefore no manifest. The manifest carries the same four values for
 *     anything inspecting a jar without loading it, which is the shell-side question.</li>
 * <li><b>The resource is namespaced</b> under {@code /diorama/}. A bare {@code /build.properties}
 *     collides with every other mod doing this on a shared classpath, and you would silently read
 *     somebody else's stamp — a diagnostic that lies is worse than none.</li>
 * <li><b>A missing stamp degrades to {@code unknown} and never throws.</b> This is diagnostic
 *     information, not a dependency, and it must never be the reason a mod fails to load.</li>
 * </ul>
 *
 * <p>Format agreed with LegendQuest, which built it first and proposed it. Deliberately copied
 * rather than shared: five copies of sixty lines is less coupling than another artifact every mod
 * depends on for a diagnostic, and this one above all must not be able to break a build.</p>
 */
public final class BuildInfo {

    private static final String RESOURCE = "/diorama/build.properties";

    private static final String UNKNOWN = "unknown";

    /** The four values, so the reading can be exercised without a classpath to stage. */
    public record Stamp(String commit, String branch, String time, String version) {}

    private static final Stamp STAMP = read(BuildInfo.class.getResourceAsStream(RESOURCE));

    /**
     * Turn the stamp resource into four values, or four {@code unknown}s.
     *
     * <p><b>Public so {@code SelfTest} can drive this method rather than a copy of it</b>, and for
     * no other reason — the accessors above are the intended surface. Widening it was the cheaper
     * of two bad options: the alternative is a fallback nobody has ever executed, and this repo has
     * a whole section on what those cost.</p>
     *
     * ⚠ The degrade path was asserted in this class's own javadoc for an hour before anything ran
     * it — "degrades to unknown and never throws" was a claim, not a result, which is precisely the
     * shape of bug this repo keeps a section about. MobHealth pointed it out.</p>
     *
     * @param in the resource, or {@code null} when there is none — a source build, a stripped jar
     */
    public static Stamp read(InputStream in) {
        if (in == null) {
            return new Stamp(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
        }
        try {
            // ⚠ ALL OR NOTHING, and this is by design rather than by luck. Properties.load parses
            // line by line and can throw PART-WAY: a valid `commit=` followed by a bad escape
            // leaves `p` holding a real commit and nothing else. Reading fields as they arrive
            // would then report a plausible-looking commit with the rest missing — worse than no
            // stamp at all, because it looks like an answer. So `p` is only read AFTER load has
            // returned, and a throw discards every field including the ones that parsed.
            //
            // ⚠ This is not a risk, it is what the parser does. CityWorld printed
            // stringPropertyNames() from inside the catch and found [commit, branch, version]
            // already populated: Properties.load BUILDS a fully-formed half-stamp and only the
            // throw stops you using it. MobHealth raised it, CityWorld measured it, and three of
            // us had it right by where the assignments happened to sit rather than by deciding.
            Properties p = new Properties();
            p.load(in);
            return new Stamp(
                    p.getProperty("commit", UNKNOWN),
                    p.getProperty("branch", UNKNOWN),
                    p.getProperty("time", UNKNOWN),
                    p.getProperty("version", UNKNOWN));
        } catch (Exception ignored) {
            // Deliberately swallowed, and deliberately catching Exception rather than IOException:
            // Properties.load throws IllegalArgumentException on a malformed unicode escape, which
            // is a corrupt-resource case rather than an I/O one. See the class note — this is
            // never the reason a mod fails to load.
            return new Stamp(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
                // Nothing useful to do, and nothing that should propagate from a diagnostic.
            }
        }
    }

    /** Short SHA, with a {@code -dirty} suffix when built from uncommitted changes. */
    public static String commit() {
        return STAMP.commit();
    }

    public static String branch() {
        return STAMP.branch();
    }

    /** UTC, ISO-8601. */
    public static String time() {
        return STAMP.time();
    }

    public static String version() {
        return STAMP.version();
    }

    /**
     * The one-line form for the startup log.
     *
     * <p>{@code 1.7.0 (build a1b2c3d4 on main, 2026-09-10T09:15:00Z)}. A {@code -dirty} on the
     * commit means uncommitted changes went into it, which is worth seeing in somebody's log
     * before you spend an hour reproducing against a tag that is not what they ran.</p>
     */
    public static String describe() {
        return STAMP.version() + " (build " + STAMP.commit() + " on " + STAMP.branch()
                + ", " + STAMP.time() + ")";
    }

    private BuildInfo() {}
}
