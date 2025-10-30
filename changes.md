* 1.12.66 - [leave initial slashes in script src alone](https://github.com/yogthos/Selmer/pull/318)
* 1.12.65 - [handle error for template without closing delimiters](https://github.com/yogthos/Selmer/pull/317)
* 1.12.64 - [support for specifying type attribute of script tag](https://github.com/yogthos/Selmer/pull/316)
* 1.12.63 - [update render behavior to match render-file](https://github.com/yogthos/Selmer/pull/315)
* 1.12.62 - render's inheritance works like render-file
* 1.12.61 - improved `java.time.Instant` to the date filter support
* 1.12.60 - add support for passing `java.time.Instant` to the date filter
* 1.12.59 - [adds resolve-arg function in selmer.parser which lets you resolve args passed to the handler of custom tags](https://github.com/yogthos/Selmer/pull/304)
* 1.12.58 - [allow negative constants in `if` expressions](https://github.com/yogthos/Selmer/pull/301)
* 1.12.57 - [`sum` tag now handles numeric literals](https://github.com/yogthos/Selmer/pull/299)
* 1.12.56 - [Add defer attr to script tags](https://github.com/yogthos/Selmer/pull/298/files)
* 1.12.55 - [switch to using ex-info for errors](https://github.com/yogthos/Selmer/pull/296)
* 1.12.54 - [Fix allowing whitespace in filters when parsing a file](https://github.com/yogthos/Selmer/pull/295)
* 1.12.53 - [adds `known-variable-paths` function to `selmer.parser`](https://github.com/yogthos/Selmer/pull/293)
* 1.12.52 - [allow tags to have string arguments containing variables/filters](https://github.com/yogthos/Selmer/issues/285)
* 1.12.51 - updated cheshire version to 5.11.0
* 1.12.50 - [add opts to known-variables params](https://github.com/yogthos/Selmer/pull/279)
* 1.12.49 - added mapping for add-filter! in selmer.parser namespace
* 1.12.48 - expose add-filter! in selmer.parser namespace for consistency
* 1.12.48 - [use synthetic name for literals in a for tag](https://github.com/yogthos/Selmer/pull/277)
* 1.12.47 - [range filter and ability to use literals in for tag](https://github.com/yogthos/Selmer/pull/275)
* 1.12.46 - [fix block inheritance NPE](https://github.com/yogthos/Selmer/issues/273)
* 1.12.45 - [fix Clojure Clojure 1.11 warnings](https://github.com/yogthos/Selmer/issues/272)
* 1.12.44 - [fix for << macro to resolve from correct namespace](https://github.com/yogthos/Selmer/pull/270)
* 1.12.43 - fix to make [string interpolation work with falsey env](https://github.com/yogthos/Selmer/pull/269)
* 1.12.42 - [changed](https://github.com/yogthos/Selmer/commit/3387e76dc2325ab2cead5deb043fc2e729fadadc) interpolation to use `resolve` instead of `eval` for safety and compatibility with Babashka
* 1.12.41 - addition of [string interpolation macro](https://github.com/yogthos/Selmer/pull/266)
* 1.12.40 - fix reflection warnings in debug pretty printer
* 1.12.39 - fix typo for sha256 filter
* 1.12.38 - [fix typo resolving Cheshire JSON](https://github.com/yogthos/Selmer/pull/264)
* 1.12.37 - [make JSON dependency pluggable, remove hard dependency on cheshire](https://github.com/yogthos/Selmer/pull/263)
* 1.12.36 - improved default pretty printing of the context map using debug tag, [allow whitespace in filter](https://github.com/yogthos/Selmer/pull/261): {{ foo | default:bar }}
* 1.12.35 - [made json-html dependency optional](https://github.com/yogthos/Selmer/pull/254), [removed commons-codec dependency](https://github.com/yogthos/Selmer/pull/256)
* 1.12.34 - [support for java.net.URLStreamHandler](https://github.com/yogthos/Selmer/pull/250)
* 1.12.33 - [fix for templates with nested with clauses](https://github.com/yogthos/Selmer/pull/249)
* 1.12.32 - [performance improvements](https://github.com/yogthos/Selmer/pull/246)
* 1.12.31 - [add forloop.previous](https://github.com/yogthos/Selmer/pull/244)
* 1.12.30 - [handle includes in blocks](https://github.com/yogthos/Selmer/pull/241)
* 1.12.29 - [Process include tags with the context of the already observed blocks](https://github.com/yogthos/Selmer/pull/240)
* 1.12.28 - [fix for async script tag](https://github.com/yogthos/Selmer/commit/d0525a94d61a149fe199147b49adb80f2f7affde)
* 1.12.27 - fix for the `:parentloop` key for the `for` tag implementation to allow accessing parent loop context in nested loops
* 1.12.26 - [fix for file path resolution on Windows](https://github.com/yogthos/Selmer/pull/232)
* 1.12.25 - [fix for elif tags](https://github.com/yogthos/Selmer/pull/230)
* 1.12.24 - updated text/javascript to application/javascript in the script tag
* 1.12.23 - [NPE fix](https://github.com/yogthos/Selmer/pull/226)
* 1.12.22 - [Allow comparing strings and keywords in the if tag like so {% if x = :lol %}](https://github.com/yogthos/Selmer/pull/224)
* 1.12.21 - [elif tag support](https://github.com/yogthos/Selmer/pull/223)
* 1.12.20 - [fix](https://github.com/yogthos/Selmer/pull/222) for handling short form comments inside tags
* 1.12.19 - [fix](https://github.com/yogthos/Selmer/pull/218) for resolving path on Windows
* 1.12.11 - updated `round` filter to return the value without wrapping it with `[:safe ]`
* 1.12.10 - updated `add` filter to return the value without wrapping it with `[:safe ]`
* 1.12.9 - Fix for tag accepts qualified keywords [PR 194](https://github.com/yogthos/Selmer/pull/194/files)
* 1.12.8 - add async support in middleware
* 1.12.7 - improved performance by setting the size in StringBuilder explicitly
* 1.12.6 - render dates in locale-aware fashion, [PR 190](https://github.com/yogthos/Selmer/pull/190)
* 1.12.5 - added java.sql.Date formatter
* 1.12.4 - switched to use native Java time API instead of Joda time, time filters now default to 24 hour format <br>
           - **breaking change:** existing Joda date format strings may not be supported by the Java time API
* 1.12.1 - renamed `:servlet-context` to `:selmer/context`
* 1.11.1 - Added support of context parameter as first argument of {% script %} and {% style %} tags
* 0.9.5 - date filter will return an empty string instead of the current date given a `nil` date
