Selmer
======

[![Continuous Integration status](https://secure.travis-ci.org/yogthos/Selmer.png)](http://travis-ci.org/yogthos/Selmer)

A fast, [Django](https://docs.djangoproject.com/en/dev/ref/templates/builtins/) inspired template system in Clojure.

## Installation

Leiningen

```clojure
[selmer "0.3.2"]
```

## Marginalia documentation

[Marginalia documentation](https://rawgithub.com/yogthos/Selmer/master/docs/uberdoc.html)

## Usage

### [Jump tp Filters](#filters)

### [Jump tp Tags](#tags)

### [Jump tp Template Inheritance](#template-inheritance)


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

When rendering files Selmer will cache the compiled template. A recompile will be triggered if the last
modified timestamp of the files changes. Alternatively you can turn caching on and off using `(selmer.parser/cache-on!)` and
 `(selmer.parser/cache-off!)` respectively. 

## Variables and Tags

Variables are used to inject dynamic content into the text of the template. The values for the variables
are looked up in the context map as can be seen in the example above. When a value is missing then an 
empty string is rendered in its place.

By default variables are defined using the double curly braces: `{{myvar}}`. 

A variables can also be nested data structures, eg:

`(render "{{person.name}}" {:person {:name "John Doe"}})`

`(render "{{foo.bar.0.baz}}" {:foo {:bar [{:baz "hi"}]}})`

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

### Built-in Filters

[add] (#add)
[addslashes](#addslashes)
[capitalize] (#capitalize)
[center] (#center)
[count] (#count)
[date] (#date)
[default] (#default)
[default-if-empty] (#default-if-empty)
[double-format] (#double-format)
[first] (#first)
[get-digit] (#get-digit)
[hash] (#hash)
[join] (#join)
[json] (#json)
[last] (#last)
[length] (#length)
[length-is] (#length-is)
[linebreaks] (#linebreaks)
[linebreaks-br] (#linebreaks-br)
[linenumbers] (#linenumbers)
[lower] (#lower)
[pluralize] (#pluralize)
[rand-nth] (#rand-nth)
[remove] (#remove)
[remove-tags] (#remove-tags)
[safe] (#safe)
[sort] (#sort)
[sort-by] (#sort-by)
[sort-by-reversed] (#sort-by-reversed)
[sort-reversed] (#sort-reversed)
[upper] (#upper)

### Built-in Tags

[block] (#block)
[comment] (#comment)
[cycle] (#cycle)
[if] (#if)
[ifequal] (#ifequal)
[include] (#include)
[extends] (#extends)
[firstof] (#firstof)
[for] (#for)
[now] (#now)
[script] (#script)
[style] (#style)
[verbatim] (#verbatim)
[with] (#with)

## Filters

In many cases you may wish to postprocess the value of a variable. For example, you might want to convert
it to upper case, pluralize it, or parse it as a date. This can be done by specifying a filter following the
name of the variable. The filters are separated using the `|` character.

For example, if we wanted to convert the variable to upper case we could write `{{user-name|upper}}`. When
rendered with `{:user-name "Yogthos"}` it would produce `YOGTHOS` as its output.

Some filters can take parameters. `{{domain|hash:"md5"}}` rendered with `{:domain "example.org"}` would produce
`1bdf72e04d6b50c82a48c7e4dd38cc69`.

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

### Built-in Filters

#### add
`(render "{{add_me|add:2:3:4}}" {:add_me 2})` => `11`

#### addslashes
Nota bene, the slashes aren't actually in the input string, but they *are* going to be in the input. Just trying to write valid Clojure code.

`(render "{{name|addslashes}}" {:name "\"Russian tea is best tea\""})` => `"\"Russian tea is best tea\""`

#### capitalize
`(render "{{name|capitalize}}" {:name "russian tea is best tea"})` => `"Russian tea is best tea"`

#### center
`(render "{{name|center:20}}" {:name "bitemyapp"})` => `"      bitemyapp     "`

#### count
`(render "{{name|count}}" {:name "Yogthos"})` => `"7"`

`(render "{{items|count}}" {:items [1 2 3 4]})` => `"4"`


#### date
`(render "{{creation-time|date:\"yyyy-MM-dd_HH:mm:ss\"}}" {:created-at (java.util.Date.)})` => `"2013-07-28_20:51:48"`

#### default
`(render "{{name|default:"I <3 ponies"}}" {:name "bitemyapp"})` => `"bitemyapp"`
`(render "{{name|default:"I <3 ponies"}}" {:name nil})` => `"I <3 ponies"`
`(render "{{name|default:"I <3 ponies"}}" {:name []})` => `"[]"`

`(render "{{name|default:"I <3 ponies"}}" {})` => `"I <3 ponies"`

#### default-if-empty
`(render "{{name|default-if-empty:"I <3 ponies"}}" {:name "bitemyapp"})` => `"bitemyapp"`
`(render "{{name|default-if-empty:"I <3 ponies"}}" {:name nil})` => `"I <3 ponies"`
`(render "{{name|default-if-empty:"I <3 ponies"}}" {:name []})` => `"I <3 ponies"`
`(render "{{name|default-if-empty:"I <3 ponies"}}" {})` => `"I <3 ponies"`

#### double-format
`(render "{{tis-a-number|double-format:2}}" {:tis-a-number 10.00001})` => `10.00`
`(render "{{tis-a-number|double-format}}" {:tis-a-number 10.00001})` => `10.0`

#### first
`(render "{{seq-of-some-sort|first}}" {:seq-of-some-sort [:dog :cat :bird :bird :bird :is :the :word]})` => `:dog`

#### get-digit
`(render "{{tis-a-number|get-digit:1}}" {:tis-a-number 12.34567})` => `7`

#### hash
available hashes: `md5`, `sha`, `sha256`, `sha384`, `sha512`

`(render "{{domain|hash:\"md5\"}}" {:domain "example.org"})` => `"1bdf72e04d6b50c82a48c7e4dd38cc69"`


#### join
`(render "{{sequence|join}}" {:sequence [1 2 3 4]})` => `"1234"`

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
Single newlines become <br />, double newlines mean new paragraph. Contenet will
be escaped by default.

`(render "{{foo|linebreaks|safe}}" {:foo "\nbar\nbaz"})` => `"<p><br />bar<br />baz</p>"`

#### linebreaks-br
like `linebreaks` but doesn't insert `<p>` tags.
`(render "{{foo|linebreaks-br|safe}}" {:foo "\nbar\nbaz"})` => `"bar<br />baz"`
#### linenumbers
Displays text with line numbers.
`(render "{{foo|linenumbers" {:foo "foo\n\bar\nbaz"})` => `"1. foo\n2. \bar\n3. baz"`
#### lower
`(render "{{foo|lower}}" {:foo "FOOBaR"})` => `"foobar"`
#### pluralize
Returns the correct (English) pluralization based on the variable. This works with many words, but certainly not all (eg. foot/feet, mouse/mice, etc.)

`(render "{{items|count}} item{{items|pluralize}}" {:items []})` => `"0 items"`

`(render "{{items|count}} item{{items|pluralize}}" {:items [1]})` => `"1 item"`

`(render "{{items|count}} item{{items|pluralize}}" {:items [1 2]})` => `"2 items"`

`(render "{{fruit|count}} tomato{{fruit|pluralize:\"es\"}}" {:fruit []})` => `"0 tomatoes"`

`(render "{{people|count}} lad{{people|pluralize:\"y\":\"ies\"}}" {:people [1]})` => `"1 lady"`

`(render "{{people|count}} lad{{people|pluralize:\"y\":\"ies\"}}" {:people [1 2]})` => `"2 ladies"`


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

#### upper
`(render "{{shout|upper}}" {:shout "hello"})` => `"HELLO"`

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
tags can also contain content and intermediate tags:

```clojure
(add-tag! :foo
  (fn [args context-map content]
    (str content))
  :bar :baz)
    
(render "{% foo %} some text {% bar %} some more text {% baz %}" {})
=>"{:foo {:args nil, :content \" some text \"}, :bar {:args nil, :content \" some more text \"}}"
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

Will cycle through the supplied argument.

`(render "{% for i in items %}<li class={% cycle \"blue\" \"white\" %}>{{i}}</li>{% endfor %}" {:items (range 5)})` => `"<li class=\"blue\">0</li><li class=\"white\">1</li><li class=\"blue\">2</li><li class=\"white\">3</li><li class=\"blue\">4</li>"`

#### extends

This tag is used to reference a parent template. The blocks in parents are recursively overridden by
the blocks from child templates.

* Note: child templates can **only** contain blocks. Any tags or text outside the blocks will be
ignored!

For example, say we have a base template called `base.html` and a child template `child.thml`:

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

`{% if condition %}yes!{% else %}no!{% endif %}`

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

you can also iterate over nested data structures, eg:

`{% for item in items %} <tr><td>{{item.name}}</td><td>{{item.age}}</td></tr> {% endfor %}`

#### now
renders current time 

`(render (str "{% now \"" date-format "\"%}") {})` => `"\"01 08 2013\""`

#### comment
ignores any content inside the block

`(render "foo bar {% comment %} baz test {{x}} {% endcomment %} blah" {})` => `"foo bar  baz test  blah"`

#### firstof
renders the first occurance of supplied keys that doesn't resolve to false:

`(render "{% firstof var1 var2 var3 %}" {:var2 "x" :var3 "not me"})` => `"x"`

#### script

The script tag will generate an HTML script tag and prepend the value of the `servlet-context` key
to the URI. When `servlet-context` key is not present then the original URI is set.

`(render "{% style \"/css/screen.css\" %}" {:servlet-context "/myapp"})` => 
```
"<link href=\"/myapp/css/screen.css\" rel=\"stylesheet\" type=\"text/css\" />"
```
#### style

The script tag will generate an HTML script tag and prepend the value of the `servlet-context` key
to the URI. When `servlet-context` key is not present then the original URI is set.

`(render "{% script \"/js/site.js\" %}" {:servlet-context "/myapp"})` => 
```
"<script src=\"/myapp/js/site.js\" type=\"text/javascript\"></script>"
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

It's also possible to specify default values for the included templates:

`base.html`

```xml
<html>
{% include "content.html" content="some content"%}	
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
