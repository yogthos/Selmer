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
