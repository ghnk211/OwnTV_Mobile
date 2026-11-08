# OwnTV translation and language guide

`tools/i18n/locales.json` is OwnTV's authoritative language catalogue. It ships exactly three
resource sets:

| id | languageTag | resourceDirectory | script | endonym |
|----|-------------|-------------------|--------|---------|
| `en-US` | `en-US` | `values/` (source) | Latn | English |
| `zh-CN` | `zh-CN` | `values-zh-rCN/` | Hans | 简体中文 |
| `zh-TW` | `zh-TW` | `values-zh-rTW/` | Hant | 繁體中文 |

English in `values/` is the complete source. Simplified and Traditional Chinese are maintained in
this repository by hand; no external translation service is connected, and no language arrives from
outside a pull request that adds both its catalogue entry and its resource directory.

## Identifiers in `locales.json`

Three identifiers look similar but have different owners:

- `languageTag`: runtime BCP-47 (`zh-CN`, `zh-TW`)
- `resourceQualifier` / `resourceDirectory`: Android (`zh-rCN` / `values-zh-rCN`)
- `weblateCode`: legacy Weblate code (`zh_Hans`, `zh_Hant`). Nothing synchronises it any more; the
  field stays because `SupportedLocales.kt` is a published data class that the apps read.

Other fields: `englishName`, `endonym`, `script` (ISO 15924), `rtl`, `tier`, `packaged`,
`pickerVisible`. `packaged` marks a locale the host apps may package (their locale filters read this
file) and `pickerVisible` gates the in-app picker; `pickerVisible=true` requires `packaged=true`.
Coverage is **never** stored here — both `validate_strings.py` and `gen_supported_locales.py` compute
it from the resource files.

## Six resource components

Every locale carries the same split files so a string change is reviewable in one place:

- `strings.xml`
- `strings_content.xml`
- `strings_player.xml`
- `strings_settings.xml`
- `strings_setup.xml`
- `strings_features.xml`

`donottranslate.xml` exists only in `values/` and holds brand + protocol constants, all marked
`translatable="false"`. A localized copy of that file is rejected.

## Adding a language

1. Translate all six components into `core/src/main/res/values-<qualifier>/`. A packaged locale
   below the 75% readiness threshold fails validation, so do not add an almost-empty directory.
2. Add its entry to `tools/i18n/locales.json` with a valid BCP-47 tag, Android qualifier, ISO 15924
   script and `tier: 1`, `packaged: true`, `pickerVisible: true`.
3. Run `python3 tools/i18n/gen_supported_locales.py`, then every validator below.
4. Never hand-edit `core/src/main/java/tv/own/owntv/core/i18n/SupportedLocales.kt`; it is generated.

## Validation

```sh
python3 tools/i18n/test_i18n_tools.py
python3 tools/i18n/validate_strings.py --report text
python3 tools/i18n/gen_supported_locales.py check
python3 tools/i18n/check_hardcoded_strings.py verify --bootstrap
python3 tools/i18n/check_number_locale.py
python3 tools/i18n/check_text_overflow.py
```

`.github/workflows/i18n.yml` runs all of them on every pull request.

`validate_strings.py` validates the catalogue schema, XML escaping, placeholder parity, translatable
flags, duplicate/translation-only keys and CLDR plural quantities. A missing localized key is valid
Android fallback and is reported, never fatal; a value that *is* present must be structurally safe.

## Clearing a literal-inventory failure

`verify` only reports; no flag makes it write, `--bootstrap` included — that flag drops the
merge-base comparison and nothing else. Two failure kinds, two fixes:

```sh
# UNCLASSIFIED — a literal exists in code but in neither reviewed file.
python3 tools/i18n/check_hardcoded_strings.py classify-safe \
    --path core/src/main/java/tv/own/owntv/example.kt --text 'SELECT 1' --category sql

# STALE CLASSIFICATION — a classified literal was edited or deleted in code.
python3 tools/i18n/check_hardcoded_strings.py prune-safe
```

Both rewrite `safe_literals.txt` and regenerate `hardcoded_baseline.txt`. Classify a literal only
when no user can ever read it; categories and their reasons are listed at the top of
`safe_literals.txt`. Text a user *can* read stays unclassified so it lands in
`hardcoded_baseline.txt` as declared debt — that file may only shrink against a pull request's merge
base. Never file real UI copy as technical to make CI green; extract it to the matching
`strings*.xml` files instead.
