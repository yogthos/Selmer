Selmer
======

[![Continuous Integration status](https://secure.travis-ci.org/yogthos/Selmer.png)](http://travis-ci.org/yogthos/Selmer)
A fast, [Django](https://docs.djangoproject.com/en/dev/ref/templates/builtins/) inspired template system for Clojure.

## Installation

#### Leiningen

[![Clojars Project](http://clojars.org/selmer/latest-version.svg)](http://clojars.org/selmer)

## Marginalia documentation

[Marginalia documentation](https://rawgithub.com/yogthos/Selmer/master/docs/uberdoc.html)

## Usage

### [Jump to Filters](#filters)

#### Built-in Filters

[abbreviate](#abbreviate)
[add](#add)
[addslashes](#addslashes)
[block.super](#blocksuper)
[capitalize](#capitalize)
[center](#center)
[count](#count)
[count-is](#count-is)
[currency-format](#currency-format)
[date](#date)
[default](#default)
[default-if-empty](#default-if-empty)
[double-format](#double-format)
[email](#email)
[empty?](#empty)
[not-empty](#not-empty)
[first](#first)
[take](#take)
[drop](#drop)
[drop-last](#drop-last)
[get-digit](#get-digit)
[hash](#hash)
[join](#join)
[json](#json)
[last](#last)
[length](#length)
[length-is](#length-is)
[linebreaks](#linebreaks)
[linebreaks-br](#linebreaks-br)
[linenumbers](#linenumbers)
[lower](#lower)
[name](#name)
[phone](#phone)
[pluralize](#pluralize)
[rand-nth](#rand-nth)
[remove](#remove)
[remove-tags](#remove-tags)
[safe](#safe)
[sort](#sort)
[sort-by](#sort-by)
[sort-by-reversed](#sort-by-reversed)
[sort-reversed](#sort-reversed)
[subs](#subs)
[sum](#sum)
[str](#str)
[title](#title)
[upper](#upper)
[urlescape](#urlescape)
[multiply](#multiply)
[divide](#divide)
[round](#round)
[between?](#between)
[replace](#replace)

### [Jump to Tags](#tags)

#### Built-in Tags

[block](#block)
[comment](#comment)
[cycle](#cycle)
[debug](#debug)
[if](#if)
[ifequal](#ifequal)
[ifunequal](#ifunequal)
[include](#include)
[extends](#extends)
[firstof](#firstof)
[for](#for)
[now](#now)
[safe](#safe-tag)
[script](#script)
[style](#style)
[verbatim](#verbatim)
[with](#with)

### [Jump to Template Inheritance](#template-inheritance)


### Templates

Selmer templates consist of plain text that contains embedded expression and filter tags. While Selmer
is primarily meant for HTML generation, it can be used for templating any text.

Selmer compiles the template files and replaces any tags with the corresponding functions for handling
dynamic content. The compiled template can then be rendered given a context map.

For example, if we wanted to render a string containing a name variable we could write the following:

```clojure
(use 'selmer.parser)

(render "Hello {{name}}!" {:name "Yogthos"})
=>"Hello Yogthos!"
```

To render a file we can call `render-file` instead:

```clojure
(use 'selmer.parser)

(render-file "home.html" {:name "Yogthos"})
```

To list the declared variables in the template:

```clojure
(known-variables "{{name}}")
=>#{:name}
```

#### \*\*Important\*\*

When rendering files Selmer will cache the compiled template. A recompile will be triggered if the last
modified timestamp of the file changes. Note that changes in files referenced by the template **will not**
trigger a recompile. This means that if your template extends or includes other templates you must touch
the file that's being rendered for changes to take effect.

Alternatively you can turn caching on and off using `(selmer.parser/cache-on!)` and
 `(selmer.parser/cache-off!)` respectively.

### Resource Path

By default the templates are located relative to the `ClassLoader` URL. If you'd like to set a custom location for the
templates, you can use `selmer.parser/set-resource-path!` to do that:

```clojure
(selmer.parser/set-resource-path! "/var/html/templates/")
```

It's also possible to set the root template path in a location relative to the resource path of the application:

```clojure
(set-resource-path! (clojure.java.io/resource "META-INF/foo/templates"))
```

This allows the templates to be refrerenced using `include` and `extends` tags without having to specify the full path.

To reset the resource path back to the default simply pass it a `nil`:

```clojure
(selmer.parser/set-resource-path! nil)
```

The application will then look for templates at this location. This can be useful if you're deploying the application
as a jar and would like to be able to modify the HTML without having to redeploy it.

By default, Selmer uses `{%` and `%}` to indicate the start and the end of an expression, while using `{{` and `}}` for for variables.
This might conflict with clientside frameworks such as AngularJS. In this case you can specify custom tags by passing
a map containing any of the following keys to the parser:

```clojure
:tag-open
:tag-close
:filter-open
:filter-close
:tag-second
```

```clojure
(render "[% for ele in foo %]{{[{ele}]}}[%endfor%]"
                 {:foo [1 2 3]}
                 {:tag-open \[
                  :tag-close \]})
=>"{{1}}{{2}}{{3}}"
```

Note that if you're using namespaces keys, such as `:foo.bar/baz`, then you will need to escape the `.` as follows:

```clojure
(parser/render "{{foo..bar/baz}}" {:foo.bar/baz "hello"})
```

## Error Handling

Selmer will attempt to validate your templates by default, if you wish to disable validation for any reason it can be done by
calling `(selmer.validator/validate-off!)`.

Whenever an error is detected by the validator an instance of `clojure.lang.ExceptionInfo` will be thrown.
The exception will contain the following keys:

* `:type` - `:selmer-validation-error`
* `:error` - the error message
* `:error-template` - the error page template
* `:template` - template file that contains the error
* `:validation-errors` - a vector of validation errors

Each error in the `:validation-errors` vector is a map containing the details specific to the error:

* `:line` - the line on which the error occurred
* `:tag` - the tag that contains the error

The template under the `:error-template` key can be used to render a friendly error page.
Selmer provides a middleware wrapper for this purpose:

```clojure
(ns myapp.handler
  (:require [selmer.middleware :refer [wrap-error-page]]
            [environ.core :refer [env]]))

...

#(if (env :dev) (wrap-error-page %) %)
```

The middleware will render a page like the one below whenever any parsing errors are encountered.

![](https://raw.github.com/yogthos/Selmer/master/error_page.png)

## Variables and Tags

Variables are used to inject dynamic content into the text of the template. The values for the variables
are looked up in the context map as can be seen in the example above. When a value is missing then an
empty string is rendered in its place.

By default variables are defined using the double curly braces: `{{myvar}}`.

A variables can also be nested data structures, eg:

`(render "{{person.name}}" {:person {:name "John Doe"}})`

`(render "{{foo.bar.0.baz}}" {:foo {:bar [{:baz "hi"}]}})`

It works with string keys too. For optimal performance, prefer maps with keyword keys. Occasional
string keys are ok, but heavily nested context maps with all string key lookups are slower to render.

`(render "{{foo.bar.baz}}" {:foo {:bar {"baz" "hi"}}})`

Tags are used to add various functionality to the template such as looping and conditions.
For example, if we wanted to create a list from a collection of items we could use the `for` tag
as follows:

```xml
<ul>
{% for item in items %}
    <li>{{item}}</li>
{% endfor %}
</ul>
```

## Filters

In many cases you may wish to postprocess the value of a variable. For example, you might want to convert
it to upper case, pluralize it, or parse it as a date. This can be done by specifying a filter following the
name of the variable. The filters are separated using the `|` character.

For example, if we wanted to convert the variable to upper case we could write `{{user-name|upper}}`. When
rendered with `{:user-name "Yogthos"}` it would produce `YOGTHOS` as its output.

Some filters can take parameters. `{{domain|hash:"md5"}}` rendered with `{:domain "example.org"}` would produce
`1bdf72e04d6b50c82a48c7e4dd38cc69`. If a parameter begins with `@` it will be looked up in the context map and,
if found, will be replaced with its value before
being passed to the filter function. For example, `@foo.bar` will treated as `(get-in context-map [:foo :bar] "@foo.bar")`.

Finally, you can easily register custom filters in addition to those already provided. A filter is simply a function
that accepts a value and returns its replacement:

```clojure
(use 'selmer.filters)

(add-filter! :embiginate clojure.string/upper-case)
(render "{{shout|embiginate}}" {:shout "hello"})
=>"HELLO"

(add-filter! :empty? empty?)
(render "{{files|empty?}}" {:files []})
=>"true"
```

by default the content of the filter will be escaped, if you'd like to make a safe filter then wrap it's body
in a vector with a `:safe` keyword:

```clojure
(add-filter! :foo  (fn [x] [:safe (.toUpperCase x)]))

(render "{{x|foo}}" {:x "<div>I'm safe</div>"})
=>"<DIV>I'M SAFE</DIV>"
```

It is possible to disable escaping (if, for example, your target format is not HTML/XML) using the `selmer.util/without-escaping` macro:

```clojure
(require '[selmer.util :refer [without-escaping]])

(without-escaping
  (render "{{x}}" {:x "I <3 NY"}))
=>"I <3 NY"
```

Alternatively, you can turn off escaping permanently in all threads with the `selmer.util/turn-off-escaping!` function.


### Built-in Filters

#### abbreviate

Abbreviate the input string to given width if it exceeds a maxium
width. If only a maximum width is given, abbreviated and maximum width
are the same. The first parameter is the maximum width, the optional
second parameter the abbreviated width.

`(render "{{f|abbreviate:19}}" {:f "an abbreviate example text"}) => "an abbreviate ex..."`

`(render "{{f|abbreviate:19:12}}" {:f "an abbreviate example text"}) => "an abbrev..."`

`(render "{{f|abbreviate:26:12}}" {:f "an abbreviate example text"}) => "an abbreviate example text"`

The last example shows: if the string fits in the maximum width the
full string is used even if the abbreviated form would be shorter.

By default `...` is used as replacement for the abbreviated part of
the string. You can easily change that with the `abbr-ellipsis` filter:

`(render "{{f|abbr-ellipsis:\"… etc. pp.\"|abbreviate:19}}" {:f "an abbreviate example text"}) => "an abbrev… etc. pp."`

`(render "{{f|abbr-ellipsis:\"\"|abbreviate:19}}" {:f "an abbreviate example text"}) => "an abbreviate examp"`

Note that the ellipsis can't be longer than the abbreviated width.

With the `abbr-left`, `abbr-right` and `abbr-middle` filters you can
also control in which position the abbreviation happens. Filter
`abbr-right` is provided for completeness, even though it's the
default.

`(render "{{f|abbr-left|abbreviate:19:12}}" {:f "an abbreviate example text"}) => "...mple text"`

`(render "{{f|abbr-middle|abbreviate:19}}" {:f "an abbreviate example text"}) => "an abbre...ple text"`

You also can combine the position and ellipsis filter:

`(render "{{f|abbr-ellipsis:\" <-- snip --> \"|abbr-middle|abbreviate:19}}" {:f "an abbreviate example text"}) => "an &lt;-- snip --&gt; ext"`

Please note that the `abbr-left`, `abbr-right`, `abbr-middle` and
`abbr-ellipsis` filters can only be used just before an `abbreviate`
filter!

#### add
Can add Integers and Doubles. If one of the parameters cannot be casted into one of the two, all parameters will be concatenated to a String.

`(render "{{add_me|add:2:3:4}}" {:add_me 2})` => `11`

`(render "{{h|add:e:l:l:o}}" {:h "h"})` => `"hello"`

#### addslashes
Nota bene, the slashes aren't actually in the input string, but they *are* going to be in the input. Just trying to write valid Clojure code.

`(render "{{name|addslashes}}" {:name "\"Russian tea is best tea\""})` => `"\"Russian tea is best tea\""`

#### block.super

Can be used inside a block to insert the content from the parent block in its place

`{% block foo %} {{block.super}} some content{% endblock %}`


#### capitalize
`(render "{{name|capitalize}}" {:name "russian tea is best tea"})` => `"Russian tea is best tea"`

#### center
`(render "{{name|center:20}}" {:name "yogthos"})` => `"      yogthos     "`

#### count
`(render "{{name|count}}" {:name "Yogthos"})` => `"7"`

`(render "{{items|count}}" {:items [1 2 3 4]})` => `"4"`

#### count-is
`(render "{{x|count-is:3}}" {:x [1 2 3]})` => `"true"`
`(render "{{x|count-is:0}}" {})` => `"true"`

#### currency-format
`"{{amount|currency-format}}" {:amount 123})` => `"$123.00"`

Uses `java.text.NumberFormat/getCurrencyInstance` for formatting the currency value.
The formatter defaults to the default locale for this instance of the Java Virtual Machine.

An ISO 639 2-letter language code can be added as a locale.

`"{{amount|currency-format:de}}" {:amount 123})` => `"€ 123,00"`

Additionally, the locale can be followed by the country code.

`"{{amount|currency-format:de:DE}}" {:amount 123})` => `"€ 123,00"`

#### date
Format a date. Supports a number of predefined formats, whose output may differ according to the current locale and / or JVM version. Valid formats are: `shortDate`, `shortTime`, `shortDateTime`, `mediumDate`, `mediumTime`, `mediumDateTime`, `longDate`, `longTime`, `longDateTime`, `fullDate`, `fullTime`, and `fullDateTime`.

`(render "{{d|date:shortDate}}" {:d (java.util.Date.)})` => `"8/3/13"`

`(render "{{d|date:shortDate}}" {:d nil})` => `""`

To more precisely control the output format, pass a format string:

`(render "{{d|date:\"yyyy-MM-dd\"}}" {:d (java.util.Date.)})` => `"2013-08-03"`

An ISO 639 2-letter language code can be added to force a particular locale:

`(render "{{now|date:\"MMMM\":fr}}" {:now (java.util.Date.)})` => `"mars"`

To conveniently render the current date, see the [now](#now) tag.

#### default
`(render "{{name|default:"I <3 ponies"}}" {:name "yogthos"})` => `"yogthos"`
`(render "{{name|default:"I <3 ponies"}}" {:name nil})` => `"I <3 ponies"`
`(render "{{name|default:"I <3 ponies"}}" {:name []})` => `"[]"`

`(render "{{name|default:"I <3 ponies"}}" {})` => `"I <3 ponies"`

#### default-if-empty
`(render "{{name|default-if-empty:"I <3 ponies"}}" {:name "yogthos"})` => `"yogthos"`
`(render "{{name|default-if-empty:"I <3 ponies"}}" {:name nil})` => `"I <3 ponies"`
`(render "{{name|default-if-empty:"I <3 ponies"}}" {:name []})` => `"I <3 ponies"`
`(render "{{name|default-if-empty:"I <3 ponies"}}" {})` => `"I <3 ponies"`

#### double-format
`(render "{{tis-a-number|double-format:2}}" {:tis-a-number 10.00001})` => `10.00`
`(render "{{tis-a-number|double-format}}" {:tis-a-number 10.00001})` => `10.0`

#### email
Renders an email address as a selectable link.

`(render "{{address|email}}" {:address "mickey@disney.com"})` => `<a href="mickey@disney.com">mickey@disney.com</a>`

Nota bene, the email filter takes an optional `validate?` argument. If it is present and equal to `false`, the email filter will process any argument as above:

`(render "{{address|email:false}}" {:address "this.is.not.an.email.address"})` => `<a href="this.is.not.an.email.address">this.is.not.an.email.address</a>`

However, if it is not present or is present but not equal to `false`, an obviously invalid email address will cause an exception to be thrown. Validation is done by a simple regular expression; it will not catch all invalid email addresses.

#### empty?
`(render "{% if xs|empty? %}foo{% endif %}" {:xs []})` => `"foo"`

#### not-empty
`(render "{% if xs|not-empty %}foo{% endif %}" {:xs [1 2]})` => `"foo"`

#### first
`(render "{{seq-of-some-sort|first}}" {:seq-of-some-sort [:dog :cat :bird :bird :bird :is :the :word]})` => `:dog`

#### take
`(render "{{seq-of-some-sort|take:3}}" {:seq-of-some-sort [:dog :cat :bird :bird :bird :is :the :word]})` => `[:dog :cat :bird]`

#### drop
`(render "{{seq-of-some-sort|drop:4}}" {:seq-of-some-sort [:dog :cat :bird :bird :bird :is :the :word]})` => `[:bird :is :the :word]`

`(render "{{seq-of-some-sort|drop:4}}" {:seq-of-some-sort [:dog :cat :bird :bird :bird :is :the :word]})` => `[:bird :is :the :word]`

`(render "{{seq-of-some-sort|drop:4|join:\" \"}}" {:seq-of-some-sort ["dog" "cat" "bird" "bird" "bird" "is" "the" "word"]})` => `bird is the word`

#### drop-last
Similar to drop:

`(render "{{seq-of-some-sort|drop-last:4}}" {:seq-of-some-sort [:dog :cat :bird :bird :bird :is :the :word]})` =\> `[:dog :cat :bird :bird]`


#### get-digit
`(render "{{tis-a-number|get-digit:1}}" {:tis-a-number 12.34567})` => `7`

#### hash
available hashes: `md5`, `sha`, `sha256`, `sha384`, `sha512`

`(render "{{domain|hash:\"md5\"}}" {:domain "example.org"})` => `"1bdf72e04d6b50c82a48c7e4dd38cc69"`


#### join
`(render "{{sequence|join}}" {:sequence [1 2 3 4]})` => `"1234"`
`(render "{{sequence|join:\", \"}}" {:sequence [1 2 3 4]})` => `"1, 2, 3, 4"`

#### json
by default content will be escaped

`(render "{{data|json}}" {:data [1 2 {:foo 27 :dan "awesome"}]})` => `"[1,2,{&quot;foo&quot;:27,&quot;dan&quot;:&quot;awesome&quot;}]"`

if you wish to render it unescaped use the `safe` filter:

`(render "{{f|json|safe}}" {:f {:foo 27 :dan "awesome"}})`


#### last
`(render "{{sequence|last}}" {:sequence 12.34567})` => `7`
`(render "{{sequence|last}}" {:sequence [1 2 3 4]})` => `4`

#### length
`(render "{{sequence|length}}" {:sequence [1 2 3 4]})` => `4`

#### length-is
`(render "{{sequence|length-is:4}}" {:sequence [1 2 3 4]})` => `true`

#### linebreaks
Single newlines become `<br />`, double newlines mean new paragraph. Content will
be escaped by default.

`(render "{{foo|linebreaks|safe}}" {:foo "\nbar\nbaz"})` => `"<p><br />bar<br />baz</p>"`

#### linebreaks-br
like `linebreaks` but doesn't insert `<p>` tags.
`(render "{{foo|linebreaks-br|safe}}" {:foo "\nbar\nbaz"})` => `"<br />bar<br />baz"`

#### linenumbers
Displays text with line numbers.
`(render "{{foo|linenumbers}}" {:foo "foo\n\bar\nbaz"})` => `"1. foo\n2. bar\n3. baz"`

#### lower
`(render "{{foo|lower}}" {:foo "FOOBaR"})` => `"foobar"`

#### name
`(render "{{foo|name}}" {:foo :foobar})` => `"foobar"`

#### number-format
`(render "{{amount|number-format:%.3f}}" {:amount 123.04455})` => `"123.045"`

An ISO 639 2-letter language code can be added as a locale.

`(render "{{amount|number-format:%.3f:de}}" {:amount 123.04455})` => `"123,045"`

#### phone
Renders a phone number as a selectable link, for use with telephony systems (including mobile phones).

`(render "{{number|phone}}" {:number "01234 567890"})` => `"<a href='tel:01234-567890'>01234 567890</a>"`

The `phone` filter takes two optional positional arguments:

* `national-prefix` The [ITU-T E.123](https://en.wikipedia.org/wiki/E.123) [international subscriber dialing prefix](https://en.wikipedia.org/wiki/List_of_country_calling_codes) to prepend in place of a leading zero. Default is do not prepend.
* `validate?` if present and equal to "false", do not throw exception if number appears invalid. Default behaviour is do throw an exception.

Both arguments are optional, but because they are positional the `national-prefix` must come before `validate?` when
both arguments are supplied.

Thus:

`(render "{{number|phone:44:true}}" {:number "01234 567890"})` => `"<a href='tel:+44-1234-567890'>01234 567890</a>"`

Validation is done by a simple regular expression; it will not catch all invalid phone numbers.

#### pluralize
Returns the correct (English) pluralization based on the variable. This works with many words, but certainly not all (eg. foot/feet, mouse/mice, etc.)

`(render "{{items|count}} item{{items|pluralize}}" {:items []})` => `"0 items"`

`(render "{{items|count}} item{{items|pluralize}}" {:items [1]})` => `"1 item"`

`(render "{{items|count}} item{{items|pluralize}}" {:items [1 2]})` => `"2 items"`

`(render "{{fruit|count}} tomato{{fruit|pluralize:\"es\"}}" {:fruit []})` => `"0 tomatoes"`

`(render "{{people|count}} lad{{people|pluralize:\"y\":\"ies\"}}" {:people [1]})` => `"1 lady"`

`(render "{{people|count}} lad{{people|pluralize:\"y\":\"ies\"}}" {:people [1 2]})` => `"2 ladies"`

`(render "{{people}} lad{{people|pluralize:\"y\":\"ies\"}}" {:people 2})` => `"2 ladies"`

#### rand-nth
returns rand-nths value from a collection:
`(render "{{foo|rand-nth}}" {:foo [1 2 3]})` => `"2"`

#### remove
removes specified characters from the string:
`(render "{{foo|remove:\"aeiou\"}}" {:foo "abcdefghijklmnop"})` => `"bcdfghjklmnp"`
#### remove-tags
Removes the specified HTML tags from the string:
`(render "{{ value|remove-tags:b:span }}" {:value "<b><span>foobar</span></b>"})` => `"foobar"`

#### safe
By default Selmer will HTML escape all variables, The `safe` filter exempts the variable from being html-escaped:

`(render "{{data}}" {:data "<foo>"})` => `"&lt;foo&gt;"`

`(render "{{data|safe}}" {:data "<foo>"})` => `"<foo>"`

#### sort
`(render "{{ value|sort }}" {:value [1 4 2 3 5]})` => `"(1 2 3 4 5)"`

#### sort-by
`(render "{{ value|sort-by:name }}" {:value [{:name "John"} {:name "Jane"}]})` => `"({:name &quot;Jane&quot;} {:name &quot;John&quot;})"`

#### sort-reversed
same as sort, but in reverse order

#### sort-by-reversed
same as sort-by, but in reverse order

#### str
Like the clojure function `str`. So you can do crazy stuff like:
`(render "{{people|length-is:2|last|str|join:\"-\"}} lad{{people|pluralize:\"y\":\"ies\"}}" {:people [1 2]})` => `"t-r-u-e ladies"`
Without raising an exception.

#### subs
Like the clojure function `subs`.
 `(render "{{s|subs:0:3:\" ..."}}", {:s "Foo bar baz"})` => `"Foo ..."`

#### title
Capitalize the words of a string
`(render "{{s|title}}" {:s "my fancy title"})` => `"My Fancy Title"`

#### upper
`(render "{{shout|upper}}" {:shout "hello"})` => `"HELLO"`

#### urlescape
`(render "{{data|urlescape}}" {:data "clojure url"})` => `"clojure+url"`

#### round

Returns the closest integer to the argument, with ties rounding up.

`(render "{{foo|round}}" {:foo 3.33333})` =\> `"3"`

#### multiply

Multiplies the value with the given number. Throws error if one of the both
values are neither Long nor Double.

`(render "{{foo|multiply:2}}" {:foo 3.3})` =\> `"6.6"`

#### divide

Multiplies the value with the given number. Throws error if one of the both
values are neither Long nor Double.

`(render "{{foo|divide:2}}" {:foo 6})` =\> `"3"`

#### replace

Replaces all occurrences of the first string with the second string. 

`(render "{{foo|replace:foo:bar}}" {:foo "foo test foo ..."})` =\> `"bar test
bar ..."`

#### between?

`(render "{{foo|between?:2:4}}" {:foo 3})` =\> `"true"`


## Tags

Selmer supports two types of tags. The tags can be inline, which means that they consist of a single
tag statement such as `include` or `extends`, or contain a body and intermediate tags,
such as `if`, `else`, `endif`.

For example if we wanted to iterate over a collection of items, we could write the following:

```clojure
(render
  "{% for user in users %}{{user.name}}{% endfor %}"
  {:users [{:name "John"} {:name "Jane"}]})
=>"JohnJane"
```

It's also possible to define custom tags using the `add-tag!` macro:

```clojure
(use 'selmer.parser)

(add-tag! :foo
  (fn [args context-map]
    (str "foo " (first args))))

(render "{% foo quux %} {% foo baz %}" {})
=>"foo quux foo baz"
```
it's also possible to add block tags. When adding a block tag, the handler
function should accept the tag arguments, the context map, and the content.
The content will be keyed on the opening tag name as can be seen below:

```clojure
(add-tag! :uppercase
          (fn [args context-map content]
            (.toUpperCase (get-in content [:uppercase :content])))
          :enduppercase)

(render "{% uppercase %}foo {{bar}} baz{% enduppercase %}" {:bar "injected"})
=>"FOO INJECTED BAZ"
```
### Built-in Tags

#### include

replaces itself with the contents of the referenced template

`{% include "path/to/comments.html" %}`

optionally, you can supply default arguments any tags matching these will have the `default` filter applied using the value supplied:

`{% include "templates/inheritance/child.html" with name="Jane Doe" greeting="Hello!" %}`

#### block

Allows specifying a block of content that can be overwritten using the template inheritance discussed below.

`{% block foo %}This text can be overridden later{% endblock %}`

#### cycle

Will cycle through the supplied values.

```
(render "{% for i in items %}<li class={% cycle \"blue\" \"white\" %}>{{i}}</li>{% endfor %}"
        {:items (range 5)})
```
=>
```
"<li class=\"blue\">0</li><li class=\"white\">1</li><li class=\"blue\">2</li><li class=\"white\">3</li><li class=\"blue\">4</li>"
```

#### debug

Pretty prints the context map passed to the template using [json-html](https://github.com/yogthos/json-html).

 ```
 (render "{% debug %}" {:foo :bar})
 ```
 =>
 ```
 <div class="jh-root">
  <table class="jh-type-object">
   <tbody><tr><th class="jh-key jh-object-key"><span class="jh-type-string">:foo</span></th>
     <td class="jh-value jh-object-value"><span class="jh-type-string">:bar</span></td></tr>
   </tbody>
  </table>
 </div>
 ```

#### extends

This tag is used to reference a parent template. The blocks in parents are recursively overridden by
the blocks from child templates.

* Note: child templates can **only** contain blocks. Any tags or text outside the blocks will be
ignored!

For example, say we have a base template called `base.html` and a child template `child.html`:

```xml
<html>
	<body>
		{% block foo %}This text can be overridden later{% endblock %}
	</body>
</html>
```

```xml
{% extends "base.html" %}
{% block foo %}<p>This text will override the text in the parent</p>{% endblock %}
```

#### if

It's an `if` -- only render the body if the conditional is true.

`{% if condition %}yes!{% endif %}`

`{% if not condition %}yes!{% endif %}`

`{% if condition %}yes!{% else %}no!{% endif %}`

it's possible to use `any` and `all` operators to check multiple values:

`(render "{% if any foo bar baz %}hello{% endif %}" {:bar "foo"})`

`(render "{% if not any foo bar baz %}hello{% endif %}" {})`

`(render "{% if all foo bar %}hello{% endif %}" {:foo "foo" :bar "bar"})`

numeric comparisons are also supported using the `=`, `<`, `>`, `<=` and `>=` operators

`(render "{% if 5 >= x %}yes!{% endif %}" {:x 3})`

`(render "{% if x <= y %}yes!{% endif %}" {:x 3 :y 5})`

`(render "{% if x = 5.0 %}yes!{% else %}no!{% endif %}" {:x 5})`

`(render "{% if x > 5 %}yes!{% else %}no!{% endif %}" {:x 6})`

`(render "{% if vals|length <= 3 %}yes!{% else %}no!{% endif %}" {:vals (range 3)})`


filters work for the conditions:

```clojure
(add-filter! :empty? empty?)
(render "{% if files|empty? %}no files{% else %}files{% endif %}"
  {:files []})
```

#### ifequal
Only render the body if the two args are equal (according to clojure.core/=).

`{% ifequal foo bar %}yes!{% endifequal %}`

`{% ifequal foo bar %}yes!{% else %}no!{% endifequal %}`

`{% ifequal foo "this also works" %}yes!{% endifequal %}`

#### ifunequal
Only render the body if the two args are unequal (according to clojure.core/=).

`{% ifunequal foo bar %}yes!{% endifunequal %}`

**for/endfor** *block*

#### for
Render the body one time for each element in the list. Each render will introduce the following variables into the context:

* `forloop.first`
* `forloop.last`
* `forloop.counter`
* `forloop.counter0`
* `forloop.revcounter`
* `forloop.revcounter0`
* `forloop.length`

`{% for x in some-list %}element: {{x}} first? {{forloop.first}} last? {{forloop.last}}{% endfor %}`

you can iterate over nested data structures, eg:

`{% for item in items %} <tr><td>{{item.name}}</td><td>{{item.age}}</td></tr> {% endfor %}`

array elements can be destructured in for loops:

`(render "{% for x,y in items %}{{x}},{{y}}{% endfor %}" {:items [["a" "b"] ["c" "d"]]})` => `"a,bc,d"`

you can also specify the default content if there are no items using the `{% empty %}` tag:

`(render "{% for i in foo %} {{i}} {% empty %}no elements{% endfor %}" {})` => `"no elements"`

filters can be used inside the for loop:

`(render "{% for x in foo.bar|sort %}{{x}}{% endfor %}" {:foo {:bar [1 4 3 5]}})` => `"1345"`


#### sum
Sums multiple variables together
`(render "{% sum foo bar baz %}" {:foo 3 :bar 2 :baz 1})` => `"6"`

#### now
renders current time

`(render (str "{% now \"dd MM yyyy\" %}") {})` => `"\"01 08 2013\""`

#### comment
ignores any content inside the block

`(render "foo bar {% comment %} baz test {{x}} {% endcomment %} blah" {})` => `"foo bar  blah"`

A short form is also available:

`(render "foo bar {# baz test {{x}} #} blah" {})` => `"foo bar  blah"`

#### firstof
renders the first occurance of supplied keys that doesn't resolve to false:

`(render "{% firstof var1 var2 var3 %}" {:var2 "x" :var3 "not me"})` => `"x"`

<h4>
<a name="user-content-safe-tag" class="anchor" href="#safe-tag" aria-hidden="true"><span class="octicon octicon-link"></span></a>safe</h4>

safe tag will prevent escaping of any content inside it:

`(render "{% safe %}{{foo|upper}}{% endsafe %}" {:foo "<foo>"})` => `<FOO>`

Note, the escaping of variables can also be controlled through the dynamic binding of `selmer.util/*escape-variables`.

#### script

The script tag will generate an HTML script tag and prepend the value of the `selmer/context` key
to the URI. When `selmer/context` key is not present then the original URI is set.

`(render "{% script \"/js/site.js\" %}" {:selmer/context "/myapp"})` =>
```
"<script src=\"/myapp/js/site.js\" type=\"text/javascript\"></script>"
```

Since 1.11.1 URI can be a name of context parameter with optional filters.

`(render "{% script path %}" {:selmer/context "/myapp" :path "/js/site.js"})` =>
```
"<script src=\"/myapp/js/site.js\" type=\"text/javascript\"></script>"
```

`(render "{% script path|upper %}" {:selmer/context "/myapp" :path "/js/site.js"})` =>
```
"<script src=\"/myapp/JS/SITE.JS\" type=\"text/javascript\"></script>"
```
#### style

The style tag will generate an HTML style tag and prepend the value of the `selmer/context` key
to the URI. When `selmer/context` key is not present then the original URI is set.

`(render "{% style \"/css/screen.css\" %}" {:selmer/context "/myapp"})` =>
```
"<link href=\"/myapp/css/screen.css\" rel=\"stylesheet\" type=\"text/css\" />"
```

Since 1.11.1 URI can be a name of context parameter with optional filters.

`(render "{% style path %}" {:selmer/context "/myapp" :path "/css/screen.css"})` =>
```
"<link href=\"/myapp/css/screen.css\" rel=\"stylesheet\" type=\"text/css\" />"
```

`(render "{% style path|upper %}" {:selmer/context "/myapp" :path "/css/screen.css"})` =>
```
"<link href=\"/myapp/CSS/SCREEN.CSS\" rel=\"stylesheet\" type=\"text/css\" />"
```
#### verbatim
prevents any tags inside from being parsed:

`(render "{% verbatim %}{{if dying}}Still alive.{{/if}}{% endverbatim %}" {})` => `"{{if dying}}Still alive.{{/if}}"`

#### with
injects the specified keys into the context map:

`(render "{% with total=business.employees|count %}{{ total }}{% endwith %}" {:business {:employees (range 5)}})` => `"5 employees"`

## Template Inheritance

### Extending Templates

Templates can inherit from other templates using the `extends` tag. When extending a template, any blocks in the parent
will be overwritten by blocks from the child with the same name. For example if we had the following scenario:

`base.html`

```xml
<html>
<body>
{% block header %}
{% endblock %}

{% block content %}
{% endblock %}

{% block footer %}
{% endblock %}
</body>
</html>
```

`child-a.html`

```xml
{% extends "base.html" %}
{% block header %}
<h1>child-a header</h1>
{% endblock %}

{% block footer %}
<p>footer</p>
{% endblock %}
```

`child-b.html`

```xml
{% extends "child-a.html" %}
{% block header %}
<h1>child-b header</h1>
{% endblock %}

{% block content %}
Some content
{% endblock %}
```

If we called `(render-file "child-b.html" {})` then the compiled template would look as follows:

```xml
<html>
<body>
{% block header %}
<h1>child-b header</h1>
{% endblock %}
{% block content %}
Some content
{% endblock %}

{% block footer %}
<p>footer</p>
{% endblock %}
</body>
</html>
```

It's also possible to include content from the parent block using the `{{block.super}}` hint. If we change `child-b.html`
to look as follows:

```xml
{% extends "child-a.html" %}
{% block header %}
{{block.super}}
<h1>child-b header</h1>
{% endblock %}

{% block content %}
Some content
{% endblock %}
```

Then we'd have the following output:

```xml
<html>
<body>
{% block header %}

<h1>child-a header</h1>

<h1>child-b header</h1>
{% endblock %}
{% block content %}
Some content
{% endblock %}

{% block footer %}
<p>footer</p>
{% endblock %}
</body>
</html>
```

### Including Templates

Templates can also `include` other templates. In this case the contents of the child are simply spliced in place
of the tag:

`base.html`

```xml
<html>
{% include "content.html" %}
</html>
```

`content.html`

```xml
<body>content</body>
```

results in:

```xml
<html>
<body>content</body>
</html>
```

It's also possible to specify default values for the included templates using
`with`:

`base.html`

```xml
<html>
{% include "content.html" with content="some content" %}
</html>
```

`content.html`

```xml
<body>{{content}}</body>
```

results in:

```xml
<html>
<body>{{content|default:"some content"}}</body>
</html>
```

You may also specify more than one value:

`base.html`

```xml
<html>
{% include "content.html" with content="some content" url="/path/to/page" %}
</html>
```


## Missing values

Missing values are by default rendered as an empty string:

```clojure
(render "{{missing}}" {})
=> ""
```
The same goes for for loops:
```clojure
(parser/render "{% for e in items %}{% endfor %}" {})
=> ""
```

It is possible to overwrite this behavior to output a different value when encountering a mising value. This is done by calling `selmer.util/set-missing-value-formatter!` to provide a function that produces the desired output.

`set-missing-value-formatter!` takes a function of two arguments, a map of info about the tag and the context map, which is called on a missing value. The function should return the value to be output in place of an empty string (which is the default from 'default-missing-value-formatter').


```clojure
(defn missing-value-fn [tag context-map]
  (str "<Missing value: " (or (:tag-value tag) (:tag-name tag)) ">"))

(selmer.util/set-missing-value-formatter! missing-value-fn)

(selmer.parser/render "{{not-here}}" {})
=> "<Missing value: not-here>"
```

or you can throw an exception:

```clojure
(defn missing-value-fn [tag context-map]
  (throw (Exception. "Nope")))

(selmer.util/set-missing-value-formatter! missing-value-fn)

(selmer.parser/render "{{not-here}}" {}) => Exception: Nope
```

When you set a custom missing value handler, by default filters are bypassed for missing values:

```clojure
(defn missing-value-fn [tag context-map]
  (str "<Missing value: " (or (:tag-value tag) (:tag-name tag)) ">"))

(selmer.util/set-missing-value-formatter! missing-value-fn)

(selmer.parser/render "{{not-here|count}}" {})
=> "<Missing value: not-here>"
```

but this can be overwritten so filters are evaluated for missing values:

```clojure
(defn missing-value-fn [tag context-map]
  (str "<Missing value: " (or (:tag-value tag) (:tag-name tag)) ">"))

(selmer.util/set-missing-value-formatter! missing-value-fn :filter-missing-values true)

(selmer.parser/render "{{not-here|count}}" {})
=> "0"
```

Although for most use cases, this will not make sense.

## Internationalization

While there is no built in support for internationalization, it can be added via a custom tag.
The following example uses the [tongue](https://github.com/tonsky/tongue) library to implement
the `i18n` tag:

```clojure
(require '[tongue.core :as tongue]
         '[selmer.parser :as parser])

(def translate
  ;; [locale key & args] => string
  (tongue/build-translate
    {:en {:animals
          {:dog "dog"
           :cat "cat"}}
     :fr {:animals
          {:dog "chien"
           :cat "chat"}}}))

(parser/add-tag! :i18n
  (fn [[k] context]
    (->> k (keyword) (translate (or (:i18n/locale context) :en)))))

(parser/render "{% i18n animals/dog %}" {})
;=> "dog"
(parser/render "{% i18n animals/dog %}" {:i18n/locale :fr})
;=> "chien"
```

[**Back To Top ⇧**](#selmer)

## License

Copyright © 2015 Dmitri Sotnikov

Distributed under the Eclipse Public License, the same as Clojure.
