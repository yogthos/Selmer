# Introduction to Selmer

Selmer is an attempt at bringing the nice, convenient, productive templating experience the Django template language provides to the unwashed Clojure masses. Ideally, there should also be opportunities to improve upon it.

## Design Goals

### Performance

This is going to be an unusually high priority. Templating performance issues have been substantial enough in the past to force us to start this repository. Ideally, the line should end here and performance issues shouldn't be a problem for virtually all web applications.

### Why

Server-side templating still matters. Server-side rendered content is still useful and necessary and not every website is destined to be a single-page application. Even in cases where a SPA is presumed, having partially rendered content can improve the experience for your users.

### API

The idea behind Django style templates is to have the templates be predictable and understandable without going on a fishing expedition through too many other templates or especially by having to trace server-side code.

The context provided for the rendering of the template should be in the form of a map or other associative collection. We're going with maps for now.

(render-file "template/filename.html" {:key val}) is the essence of it.

### Basic functionality

{:title "my value"} and a template looking like:

    <h1>{{title}}</h1>

Should render:

    <h1>my value</h1>

Given a template like:

    <p>Trolololololololol</p><br><br><br><br><br><br><br><br><br>
    <!-- Do your eyes burn from un-semantic markup yet? -->
    {% if blah %}
    LET'S ROCK
    {% else %}
    <p>or not.</p>
    {% endif %}

Provided a context of {:blah false}, it should render:

    <p>Trolololololololol</p><br><br><br><br><br><br><br><br><br>
    <!-- Do your eyes burn from un-semantic markup yet? -->
    <p>or not.</p>
