package com.devdayton.onecodeforai.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Stateless JSON codec for [FilterConfig].
 *
 * Mirrors PresetManager's schema exactly (including the [useMaxSize] default
 * of true) so configs saved by either path remain interchangeable: a profile
 * written by the preset manager can be read by this codec and vice versa,
 * without a translation layer.
 *
 * ### Schema (Tree v2)
 *
 * The on-disk representation is a single JSON object with the following
 * keys:
 *
 *  - excludeBinaries (boolean, default true)
 *  - respectGitignore (boolean, default true)
 *  - useMaxSize (boolean, default true)
 *  - maxSizeKb (integer, default 512)
 *  - inclusions (array of strings)
 *  - exclusions (array of strings)
 *  - stubPatterns (array of strings)
 *  - useGit (boolean, default false)
 *  - gitRef (string or null)
 *
 * ### Intentional absences
 *
 *  - No excludedPaths. The field was part of earlier versions of
 *    [FilterConfig] and represented manual per-file exclusions stored
 *    inside the profile. Tree v2 moved that responsibility to
 *    [TreeOverrides], which is persisted separately under the project's
 *    .platform/plugins/com.devdayton.onecodeforai/config.json and only on
 *    Generate, so manual choices are scoped to the current project and
 *    never travel with a profile. This codec therefore neither writes nor
 *    reads excludedPaths; see [fromJson] for the tolerant read path that
 *    keeps old profiles loadable.
 *  - No stubPaths either. Same reasoning: the per-path half of the stub
 *    feature lives in [TreeOverrides.stubPaths], not in the profile. Only
 *    the rule-based half ([FilterConfig.stubPatterns]) is stored here.
 *
 * Keeping the codec narrow is what allows a saved profile to be portable
 * across projects without dragging manual paths along with it. Any field
 * that describes "this specific user decided to exclude this specific
 * file in this specific project" belongs in [TreeOverrides], and any field
 * that describes "this profile includes files matching this pattern"
 * belongs here.
 *
 * ### Forward and backward compatibility
 *
 *  - Forward: unknown keys written by a future version are ignored on
 *    read, because [JSONObject] access goes through opt* methods with
 *    explicit defaults. An old version of the plugin can therefore load a
 *    profile saved by a newer one without crashing, and simply lose the
 *    fields it does not know about on the next write.
 *  - Backward: every field added since the first public release has a
 *    default that preserves the pre-field behaviour. excludedPaths is the
 *    only removed field, and it is handled explicitly below.
 *  - Corrupted input: [fromJson] never throws. A truncated, malformed, or
 *    type-mismatched JSON object returns the documented default
 *    [FilterConfig] instead of propagating a JSONException into the
 *    settings page or the configuration dialog. This is the same contract
 *    PresetManager has always had for its profiles.
 */
object FilterConfigCodec {

    /**
     * Serializes [config] to a compact JSON string.
     *
     * The result is the canonical on-disk representation used by the preset
     * manager. It intentionally does not call [FilterConfig.normalized]
     * before writing, so the caller's data is stored verbatim: the codec is
     * a transport layer, not a validator. Normalization happens at the read
     * sites that actually consume the config (the dialog, the filter) so
     * that a user inspecting the saved JSON sees exactly what they saved.
     *
     * gitRef is written as [JSONObject.NULL] when absent rather than
     * omitted, so that a round-trip through this codec preserves the
     * distinction between "the user cleared the ref" and "this profile
     * predates the ref field". [fromJson] converts the [JSONObject.NULL]
     * sentinel back to a Kotlin null.
     *
     * stubPatterns is always emitted, even when empty. Emitting an empty
     * array instead of omitting the key keeps the schema readable for
     * anyone inspecting a saved profile and makes future diffs of the file
     * smaller (adding the first stub pattern produces a one-line diff
     * instead of a multi-line one).
     */
    fun toJson(config: FilterConfig): String = JSONObject().apply {
        put("excludeBinaries", config.excludeBinaries)
        put("respectGitignore", config.respectGitignore)
        put("useMaxSize", config.useMaxSize)
        put("maxSizeKb", config.maxSizeKb)
        put("inclusions", JSONArray(config.inclusions))
        put("exclusions", JSONArray(config.exclusions))
        put("stubPatterns", JSONArray(config.stubPatterns))
        put("useGit", config.useGit)
        put("gitRef", config.gitRef ?: JSONObject.NULL)
    }.toString()

    /**
     * Deserializes a JSON string produced by [toJson] (or by the equivalent
     * path in PresetManager) into a [FilterConfig].
     *
     * ### Failure policy
     *
     * Any exception raised while parsing — a missing brace, a string where
     * a boolean was expected, a JSON array of numbers where strings were
     * expected — is caught and converted into the documented default
     * [FilterConfig]. This is a deliberate choice: a corrupted profile must
     * not prevent the settings page or the configuration dialog from
     * opening, because the user's only recourse (delete the profile,
     * reopen the dialog) requires those screens to work. The alternative —
     * propagating the exception — would produce a circular failure where
     * the corrupted data blocks the very UI needed to remove it.
     *
     * ### Legacy excludedPaths handling
     *
     * Profiles saved by pre-Tree-v2 builds may contain an excludedPaths
     * array. This codec does not read it: the per-file decisions it held
     * are now the responsibility of [TreeOverrides], and silently
     * rehydrating them into a profile would defeat the whole point of
     * separating the two scopes. The key is simply absent from the
     * resulting [FilterConfig], which is exactly what a caller that never
     * knew about the field would expect. Users upgrading from an older
     * build therefore see their manual exclusions disappear once — a
     * behavior that is called out explicitly in the release notes so it
     * does not look like a bug.
     *
     * ### Normalization
     *
     * The result is passed through [FilterConfig.normalized] before being
     * returned. A profile saved by an older version, hand-edited, or
     * written by a future version with looser constraints is therefore
     * guaranteed to come out canonical (trimmed lists, no duplicates,
     * positive size limit) without the caller having to remember to
     * normalize.
     *
     * gitRef uses the optString overload rather than getString so that a
     * [JSONObject.NULL] value is returned as the string "null", which is
     * then filtered out. This is the same idiom PresetManager uses, and it
     * is what keeps the two paths interoperable.
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
            useGit = obj.optBoolean("useGit", false),
            gitRef = obj.optString("gitRef").takeIf { it.isNotBlank() && it != "null" }
        ).normalized()
    }.getOrDefault(FilterConfig())

    /**
     * Reads a list-of-strings field from a [JSONObject] tolerantly.
     *
     * Three failure modes are handled without throwing:
     *
     *  - Missing key. optJSONArray returns null and the result is an empty
     *    list, matching the default of every list field in [FilterConfig].
     *    This is the common case for profiles saved before a field was
     *    introduced (e.g. stubPatterns in a pre-Tree-v2 profile).
     *  - Wrong type. A JSON object or scalar stored where an array was
     *    expected also returns null from optJSONArray, so the same
     *    empty-list fallback applies.
     *  - Non-string elements. optString on a numeric or boolean element
     *    returns its string representation, which is then trimmed. This is
     *    lenient on purpose: a hand-edited profile with numeric entries
     *    (unlikely, but possible) ends up with their string form rather
     *    than crashing, and the resulting patterns simply fail to match
     *    anything, which is the safest of all failure modes.
     *
     * Empty strings after trimming are dropped, because an empty glob is
     * never a valid rule and silently turning it into "matches everything"
     * would be a security surprise.
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