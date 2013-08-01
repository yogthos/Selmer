Selmer
======

[![Continuous Integration status](https://secure.travis-ci.org/yogthos/Selmer.png)](http://travis-ci.org/yogthos/Selmer)

A fast, [Django](https://docs.djangoproject.com/en/dev/ref/templates/builtins/) inspired template system in Clojure.

## Installation

Leiningen

```clojure
[selmer "0.2.6"]
```

## Marginalia documentation

[Marginalia documentation](https://rawgithub.com/yogthos/Selmer/master/docs/uberdoc.html)

## Usage

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

### Tag Types

#### Variables

Variables are used to inject dynamic content into the text of the template. The values for the variables
are looked up in the context map as can be seen in the example above. When a value is missing then an 
empty string is rendered in its place.

By default variables are defined using the double curly braces: `{{myvar}}`. 

A variables can also be nested data structures, eg:

`(render "{{person.name}}" {:person {:name "John Doe"}})`

`(render "{{foo.bar.0.baz}}" {:foo {:bar [{:baz "hi"}]}})`

#### Filters

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

#### Default Filters

**upper**

`(render "{{shout|upper}}" {:shout "hello"})` => `"HELLO"`

**date**

`(render "{{creation-time|date:\"yyyy-MM-dd_HH:mm:ss\"}}" {:created-at (java.util.Date.)})` => `"2013-07-28_20:51:48"`

**hash**

`(render "{{domain|hash:\"md5\"}}" {:domain "example.org"})` => `"1bdf72e04d6b50c82a48c7e4dd38cc69"`

**count**

`(render "{{name|count}}" {:name "Yogthos"})` => `"7"`

`(render "{{items|count}}" {:items [1 2 3 4]})` => `"4"`

**pluralize**

Returns the correct (English) pluralization based on the variable. This works with many words, but certainly not all (eg. foot/feet, mouse/mice, etc.)

`(render "{{items|count}} item{{items|pluralize}}" {:items []})` => `"0 items"`

`(render "{{items|count}} item{{items|pluralize}}" {:items [1]})` => `"1 item"`

`(render "{{items|count}} item{{items|pluralize}}" {:items [1 2]})` => `"2 items"`

`(render "{{fruit|count}} tomato{{fruit|pluralize:\"es\"}}" {:fruit []})` => `"0 tomatoes"`

`(render "{{people|count}} lad{{people|pluralize:\"y\":\"ies\"}}" {:people [1]})` => `"1 lady"`

`(render "{{people|count}} lad{{people|pluralize:\"y\":\"ies\"}}" {:people [1 2]})` => `"2 ladies"`

**json**

`(render "{{data|json}}" {:data [1 2 {:foo 27 :dan "awesome"}]})` => `"[1,2,{&quot;foo&quot;:27,&quot;dan&quot;:&quot;awesome&quot;}]"`

**safe**

By default Selmer will HTML escape all variables, The `safe` filter exempts the variable from being html-escaped:

`(render "{{data}}" {:data "<foo>"})` => `"&lt;foo&gt;"`

`(render "{{data|safe}}" {:data "<foo>"})` => `"<foo>"`

**Tags**

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

#### Default Tags

**include**

replaces itself with the contents of the referenced template

`{% include "path/to/comments.html" %}`

**block**

Allows specifying a block of content that can be overwritten using the template inheritance discussed below.

`{% block foo %}This text can be overridden later{% endblock %}`

**block.super**

Can be used inside a block to insert the content from the parent block in its place

`{% block foo %} {{block.super}} some content{% endblock %}`

**extends**

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

**if**

It's an `if` -- only render the body if the conditional is true.

`{% if condition %}yes!{% endif %}`

`{% if condition %}yes!{% else %}no!{% endif %}`

filters work for the conditions:

```clojure
(add-filter! :empty? empty?)
(render "{% if files|empty? %}no files{% else %}files{% endif %}"
  {:files []})
```

**ifequal/endifequal** *block*

Only render the body if the two args are equal (according to clojure.core/=).

`{% ifequal foo bar %}yes!{% endifequal %}`

`{% ifequal foo bar %}yes!{% else %}no!{% endifequal %}`

`{% ifequal foo "this also works" %}yes!{% endifequal %}`

**for/endfor** *block*

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



