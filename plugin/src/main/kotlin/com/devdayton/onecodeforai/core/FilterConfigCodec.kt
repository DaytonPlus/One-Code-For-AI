package com.devdayton.onecodeforai.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Stateless JSON codec for [FilterConfig].
 *
 * Mirrors PresetManager's schema exactly (including the [FilterConfig.useMaxSize]
 * default of `true`) so configs saved by either path remain interchangeable:
 * a profile written by the preset manager can be read by this codec and
 * vice versa, without a translation layer.
 *
 * ### Schema (Tree v2, section-toggle patch)
 *
 * The on-disk representation is a single JSON object with the following
 * keys. Defaults shown are what [fromJson] substitutes when the key is
 * missing, and what a caller that never wrote the key would get back on
 * the round-trip:
 *
 *  - `excludeBinaries` (boolean, default `true`)
 *  - `respectGitignore` (boolean, default `true`)
 *  - `useMaxSize` (boolean, default `true`)
 *  - `maxSizeKb` (integer, default `512`)
 *  - `inclusions` (array of strings)
 *  - `exclusions` (array of strings)
 *  - `stubPatterns` (array of strings)
 *  - `enableInclusions` (boolean, default `true`)   — section-toggle patch
 *  - `enableExclusions` (boolean, default `true`)   — section-toggle patch
 *  - `enableStubPatterns` (boolean, default `true`) — section-toggle patch
 *  - `useGit` (boolean, default `false`)
 *  - `gitRef` (string or `null`)
 *
 * ### Why the three new enable flags exist
 *
 * [FilterConfig.enableInclusions], [FilterConfig.enableExclusions] and
 * [FilterConfig.enableStubPatterns] are stored as explicit booleans, not
 * derived from "is the corresponding list empty". A user who disables a
 * section in the configuration dialog expects two things on the next
 * open:
 *
 *  - the section is still disabled, and
 *  - the rules they had entered are still there, ready for when they
 *    turn the section back on.
 *
 * Deriving the flag from the list state would violate the second
 * expectation as soon as the section was disabled — the obvious
 * implementation, "disabled means empty list", destroys the very data
 * the toggle is supposed to preserve. Storing the flag explicitly keeps
 * the toggle reversible and matches the way `respectGitignore` already
 * works (the checkbox is independent of the rules the `.gitignore`
 * file contains).
 *
 * Because the flags are separate from the lists, the codec must
 * serialise both. An old profile that predates the flags loads with the
 * documented default (`true`) and therefore behaves exactly as it did
 * before the flags existed — the round-trip through this codec does not
 * silently disable anything a user had enabled.
 *
 * ### Intentional absences
 *
 *  - **No `excludedPaths`.** The field was part of earlier versions of
 *    [FilterConfig] and represented manual per-file exclusions stored
 *    inside the profile. Tree v2 moved that responsibility to
 *    [TreeOverrides], which is persisted separately under the project's
 *    `.platform/plugins/com.devdayton.onecodeforai/config.json` and
 *    only on Generate, so manual choices are scoped to the current
 *    project and never travel with a profile. This codec therefore
 *    neither writes nor reads `excludedPaths`; see [fromJson] for the
 *    tolerant read path that keeps old profiles loadable.
 *  - **No `stubPaths` either.** Same reasoning: the per-path half of
 *    the stub feature lives in [TreeOverrides.stubPaths], not in the
 *    profile. Only the rule-based half ([FilterConfig.stubPatterns]) is
 *    stored here.
 *
 * Keeping the codec narrow is what allows a saved profile to be portable
 * across projects without dragging manual paths along with it. Any field
 * that describes "this specific user decided to exclude this specific
 * file in this specific project" belongs in [TreeOverrides], and any
 * field that describes "this profile includes files matching this
 * pattern" belongs here. The three enable flags are a deliberate
 * exception to the "no per-project state" rule: they describe a
 * *preference about the profile's own rules*, not about any specific
 * file, so they belong with the rules they guard.
 *
 * ### Forward and backward compatibility
 *
 *  - **Forward.** Unknown keys written by a future version are ignored
 *    on read, because [JSONObject] access goes through `opt*` methods
 *    with explicit defaults. An old version of the plugin can therefore
 *    load a profile saved by a newer one without crashing, and simply
 *    lose the fields it does not know about on the next write.
 *  - **Backward.** Every field added since the first public release has
 *    a default that preserves the pre-field behaviour. `excludedPaths`
 *    is the only removed field, and it is handled explicitly by the
 *    tolerant read path. The three section flags are additive: an old
 *    profile without them loads with `true` for all three, which is
 *    exactly the pre-toggle behaviour (rules were always applied).
 *  - **Corrupted input.** [fromJson] never throws. A truncated,
 *    malformed, or type-mismatched JSON object returns the documented
 *    default [FilterConfig] instead of propagating a `JSONException`
 *    into the settings page or the configuration dialog. This is the
 *    same contract PresetManager has always had for its profiles.
 *
 * ### Interaction with [FilterConfig.normalized]
 *
 * [toJson] does not call [FilterConfig.normalized] before writing, and
 * [fromJson] does not call it after reading (in the sense that the
 * normalized form is what the codec produces for the caller, but the
 * codec does not additionally re-normalize anything already normalized
 * by the caller). This keeps the two responsibilities separate:
 *
 *  - The codec is a transport layer: it writes exactly what it is
 *    given, and reads back exactly what is stored.
 *  - Normalization is a domain concern: the configuration dialog and
 *    the preset manager both call [FilterConfig.normalized] before
 *    consuming a config, so the effect is present at every call site
 *    that matters.
 *
 * The one exception is [fromJson], which does call
 * [FilterConfig.normalized] on its result. That is deliberate: a
 * hand-edited profile or a profile saved by a version whose normalizer
 * was looser must still come out canonical, and the cost is trivial.
 * The asymmetry (write raw, read normalized) means a round-trip through
 * the codec is idempotent from the second call onward, which is what
 * callers expect.
 */
object FilterConfigCodec {

    /**
     * Serializes [config] to a compact JSON string.
     *
     * The result is the canonical on-disk representation used by the
     * preset manager. It intentionally does not call
     * [FilterConfig.normalized] before writing, so the caller's data is
     * stored verbatim: the codec is a transport layer, not a validator.
     * Normalization happens at the read sites that actually consume the
     * config (the dialog, the filter) so that a user inspecting the
     * saved JSON sees exactly what they saved.
     *
     * `gitRef` is written as [JSONObject.NULL] when absent rather than
     * omitted, so that a round-trip through this codec preserves the
     * distinction between "the user cleared the ref" and "this profile
     * predates the ref field". [fromJson] converts the [JSONObject.NULL]
     * sentinel back to a Kotlin `null`.
     *
     * `stubPatterns`, `inclusions` and `exclusions` are always emitted,
     * even when empty. Emitting an empty array instead of omitting the
     * key keeps the schema readable for anyone inspecting a saved
     * profile and makes future diffs of the file smaller (adding the
     * first entry produces a one-line diff instead of a multi-line
     * one).
     *
     * The three section enable flags are always emitted as well, even
     * when `true` (which is the default). This is the one place where
     * the codec does not follow "omit defaults" — the flags are a
     * user-facing toggle, and a reader inspecting a saved profile
     * should be able to tell at a glance which sections are disabled
     * without having to know that `true` is the default. The extra
     * bytes are negligible compared to the readability win.
     */
    fun toJson(config: FilterConfig): String = JSONObject().apply {
        put("excludeBinaries", config.excludeBinaries)
        put("respectGitignore", config.respectGitignore)
        put("useMaxSize", config.useMaxSize)
        put("maxSizeKb", config.maxSizeKb)
        put("inclusions", JSONArray(config.inclusions))
        put("exclusions", JSONArray(config.exclusions))
        put("stubPatterns", JSONArray(config.stubPatterns))
        put("enableInclusions", config.enableInclusions)
        put("enableExclusions", config.enableExclusions)
        put("enableStubPatterns", config.enableStubPatterns)
        put("useGit", config.useGit)
        put("gitRef", config.gitRef ?: JSONObject.NULL)
    }.toString()

    /**
     * Deserializes a JSON string produced by [toJson] (or by the
     * equivalent path in `PresetManager`) into a [FilterConfig].
     *
     * ### Failure policy
     *
     * Any exception raised while parsing — a missing brace, a string
     * where a boolean was expected, a JSON array of numbers where
     * strings were expected — is caught and converted into the
     * documented default [FilterConfig]. This is a deliberate choice: a
     * corrupted profile must not prevent the settings page or the
     * configuration dialog from opening, because the user's only
     * recourse (delete the profile, reopen the dialog) requires those
     * screens to work. The alternative — propagating the exception —
     * would produce a circular failure where the corrupted data blocks
     * the very UI needed to remove it.
     *
     * ### Legacy `excludedPaths` handling
     *
     * Profiles saved by pre-Tree-v2 builds may contain an
     * `excludedPaths` array. This codec does not read it: the per-file
     * decisions it held are now the responsibility of [TreeOverrides],
     * and silently rehydrating them into a profile would defeat the
     * whole point of separating the two scopes. The key is simply
     * absent from the resulting [FilterConfig], which is exactly what a
     * caller that never knew about the field would expect. Users
     * upgrading from an older build therefore see their manual
     * exclusions disappear once — a behavior that is called out
     * explicitly in the release notes so it does not look like a bug.
     *
     * ### Section enable flags on legacy profiles
     *
     * Profiles saved before the section-toggle patch do not contain
     * `enableInclusions`, `enableExclusions` or `enableStubPatterns`.
     * Those keys fall through to the `optBoolean(..., true)` default, so
     * a pre-patch profile loads with all three sections enabled —
     * which is exactly the pre-patch behaviour. A user who never opened
     * the settings page after the upgrade therefore sees no change, and
     * a user who did open it sees their toggles intact on the next
     * open.
     *
     * The default is `true` rather than `false` on purpose: making a
     * disabled default would silently strip every inclusion from every
     * legacy profile, which is the worst possible failure mode for a
     * migration — the user's rules would still be in the JSON, but the
     * export would ignore them, and there would be no way to tell
     * whether the plugin was broken or the profile was misconfigured.
     *
     * ### Normalization
     *
     * The result is passed through [FilterConfig.normalized] before
     * being returned. A profile saved by an older version,
     * hand-edited, or written by a future version with looser
     * constraints is therefore guaranteed to come out canonical
     * (trimmed lists, no duplicates, positive size limit) without the
     * caller having to remember to normalize.
     *
     * [FilterConfig.normalized] deliberately does **not** touch the
     * three section enable flags, so a config returned by this method
     * preserves the exact flag state stored on disk. The flags are the
     * user's explicit intent; normalizing them by deriving from the
     * list state would break the toggle's reversibility (see the
     * [FilterConfig] KDoc).
     *
     * `gitRef` uses the `optString` overload rather than `getString` so
     * that a [JSONObject.NULL] value is returned as the string
     * `"null"`, which is then filtered out. This is the same idiom
     * `PresetManager` uses, and it is what keeps the two paths
     * interoperable.
     */
    fun fromJson(json: String): FilterConfig = runCatching {
        val obj = JSONObject(json)
        FilterConfig(
            excludeBinaries = obj.optBoolean("excludeBinaries", true),
            respectGitignore = obj.optBoolean("respectGitignore", true),
            useMaxSize = obj.optBoolean("useMaxSize", true),
            maxSizeKb = obj.optInt("maxSizeKb", FilterConfig.DEFAULT_MAX_SIZE_KB),
            inclusions = obj.readStringList("inclusions"),
            exclusions = obj.readStringList("exclusions"),
            stubPatterns = obj.readStringList("stubPatterns"),
            enableInclusions = obj.optBoolean("enableInclusions", true),
            enableExclusions = obj.optBoolean("enableExclusions", true),
            enableStubPatterns = obj.optBoolean("enableStubPatterns", true),
            useGit = obj.optBoolean("useGit", false),
            gitRef = obj.optString("gitRef").takeIf { it.isNotBlank() && it != "null" }
        ).normalized()
    }.getOrDefault(FilterConfig())

    /**
     * Reads a list-of-strings field from a [JSONObject] tolerantly.
     *
     * Three failure modes are handled without throwing:
     *
     *  - **Missing key.** `optJSONArray` returns `null` and the result is
     *    an empty list, matching the default of every list field in
     *    [FilterConfig]. This is the common case for profiles saved
     *    before a field was introduced (e.g. `stubPatterns` in a
     *    pre-Tree-v2 profile).
     *  - **Wrong type.** A JSON object or scalar stored where an array
     *    was expected also returns `null` from `optJSONArray`, so the
     *    same empty-list fallback applies.
     *  - **Non-string elements.** `optString` on a numeric or boolean
     *    element returns its string representation, which is then
     *    trimmed. This is lenient on purpose: a hand-edited profile
     *    with numeric entries (unlikely, but possible) ends up with
     *    their string form rather than crashing, and the resulting
     *    patterns simply fail to match anything, which is the safest of
     *    all failure modes.
     *
     * Empty strings after trimming are dropped, because an empty glob is
     * never a valid rule and silently turning it into "matches
     * everything" would be a security surprise.
     *
     * The helper is shared by every list field. It intentionally does
     * **not** filter duplicates or normalize case; those are the
     * responsibility of [FilterConfig.normalized], which runs once on
     * the whole config rather than once per list. Splitting the
     * responsibilities this way keeps this helper cheap (it is called
     * four times per config) and keeps the normalization policy in a
     * single place.
     */
    private fun JSONObject.readStringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}