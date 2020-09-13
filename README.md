<a href="https://www.taoensso.com" title="More stuff by @ptaoussanis at www.taoensso.com">
<img src="https://www.taoensso.com/taoensso-open-source.png" alt="Taoensso open-source" width="400"/></a>

**[CHANGELOG]** | [API] | current [Break Version]:

```clojure
[com.taoensso/timbre "4.10.0"]    ; Stable
[com.taoensso/timbre "5.0.0-RC1"] ; Dev, please see CHANGELOG for details
```

> See [here](https://taoensso.com/clojure/backers) if you're interested in helping support my open-source work, thanks! - Peter Taoussanis

# Timbre

## A pure Clojure/Script logging library

Java logging is a Kafkaesque mess of complexity that buys you little. It can be comically hard to get even the simplest logging working, and it just gets worse at scale.

Timbre offers an **all Clojure/Script** alternative that's fast, deeply flexible, easy to configure, and that **works out the box**.  No XML.

Supports optional interop with [tools.logging](https://github.com/ptaoussanis/timbre/blob/master/src/taoensso/timbre/tools/logging.clj) and [log4j/logback/slf4j](https://github.com/fzakaria/slf4j-timbre).

Happy hacking!

## Features
 * Full **Clojure** + **ClojureScript** support (v4+).
 * No XML or properties files. **A single, simple config map**, and you're set.
 * Simple, flexible **fn appender model** with **middleware**.
 * **Great performance** at any scale.
 * Filter logging by levels, **namespace whitelist/blacklist patterns**, and more.
 * **Zero overhead** with **complete Clj+Cljs elision** for compile-time level/ns filters.
 * Useful built-in appenders for **out-the-box** Clj+Cljs logging.
 * Powerful, easy-to-configure **rate limits** and **async logging**.
 * [Logs as Clojure values][] (v3+).
 * Small, simple, cross-platform codebase.

## 3rd-party tools, appenders, etc.

Link                     | Description
------------------------ | -----------------------------------------------------
[@fzakaria/slf4j-timbre] | Route log4j/logback/sfl4j log output to Timbre
[@palletops/log-config]  | Library to help manage Timbre logging config
Your link here?          | **PR's welcome!**

## Getting started

Add the necessary dependency to your project:

```clojure
[com.taoensso/timbre "4.10.0"]
```

And setup your namespace imports:

```clojure
(ns my-clj-ns ; Clojure namespace
  (:require
    [taoensso.timbre :as timbre
      :refer [log  trace  debug  info  warn  error  fatal  report
              logf tracef debugf infof warnf errorf fatalf reportf
              spy get-env]]))

(ns my-cljs-ns ; ; ClojureScript namespace
  (:require
    [taoensso.timbre :as timbre
      :refer-macros [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]]))
```

> You can also call `(timbre/refer-timbre)` to configure Clj ns referrals **automatically**.

### Logging

By default, Timbre gives you basic `println` and `js/console` (v4+) output at a `:debug` log level:

```clojure
(info "This will print") => nil
%> 15-Jun-13 19:18:33 localhost INFO [my-app.core] - This will print

(spy :info (* 5 4 3 2 1)) => 120
%> 15-Jun-13 19:19:13 localhost INFO [my-app.core] - (* 5 4 3 2 1) => 120

(defn my-mult [x y] (info "Lexical env:" (get-env)) (* x y)) => #'my-mult
(my-mult 4 7) => 28
%> 15-Jun-13 19:21:53 localhost INFO [my-app.core] - Lexical env: {x 4, y 7}

(trace "This won't print due to insufficient log level") => nil
```

First-argument exceptions generate a nicely cleaned-up stack trace using [io.aviso.exception][] (Clj only):

```clojure
(info (Exception. "Oh noes") "arg1" "arg2")
%> 15-Jun-13 19:22:55 localhost INFO [my-app.core] - arg1 arg2
java.lang.Exception: Oh noes
<Stacktrace>
```

Other utils include: `log-errors`, `log-and-rethrow-errors`, `logged-future`, and `handle-uncaught-jvm-exceptions!` (please see the [API] for details).

#### Disabling stacktrace colors

ANSI colors are enabled by default for stacktraces. To turn these off (e.g. for log files or emails), you can add the following entry to your top-level config **or** individual appender map/s:

```clojure
:output-fn (partial timbre/default-output-fn {:stacktrace-fonts {}})
```

And/or you can set the `TIMBRE_DEFAULT_STACKTRACE_FONTS` environment variable (supports edn).

### Data flow

Timbre's inherently a very simple design, no magic. It's just Clojure data and functions:

 1. Unfiltered logging calls generate a **data map**: `{:level _ :?ns-str _ ...}`
 2. The resulting data map passes through any **middleware fns**, `(fn [data]) -> ?data`
 3. The resulting data map is sent to all **appender fns**, `(fn [data]) -> ?effects`

### Configuration

This is the biggest win over Java logging IMO. 

**All** of Timbre's behaviour is controlled through a single, simple Clojure map.

> See `timbre/example-config` for Timbre's default config map

```clojure
(def example-config
  "An example Timbre v4 config map.

  APPENDERS
    An appender is a map with keys:
      :min-level       ; Level keyword, or nil (=> no minimum level)
      :enabled?        ;
      :async?          ; Dispatch using agent? Useful for slow appenders (clj only)
      :rate-limit      ; [[ncalls-limit window-ms] <...>], or nil
      :output-fn       ; Optional override for inherited (fn [data]) -> string
      :timestamp-opts  ; Optional override for inherited {:pattern _ :locale _ :timezone _}
      :ns-whitelist    ; Optional, stacks with active config's whitelist
      :ns-blacklist    ; Optional, stacks with active config's blacklist
      :fn              ; (fn [data]) -> side effects, with keys described below

    An appender's fn takes a single data map with keys:
      :config          ; Entire config map (this map, etc.)
      :appender-id     ; Id of appender currently dispatching
      :appender        ; Entire map of appender currently dispatching
      :instant         ; Platform date (java.util.Date or js/Date)
      :level           ; Keyword
      :error-level?    ; Is level e/o #{:error :fatal}?
      :?ns-str         ; String,  or nil
      :?file           ; String,  or nil
      :?line           ; Integer, or nil ; Waiting on CLJ-865
      :?err            ; First-arg platform error, or nil
      :vargs           ; Vector of raw args
      :output_         ; Forceable - final formatted output string created
                       ; by calling (output-fn <this-data-map>)
      :msg_            ; Forceable - args as a string
      :timestamp_      ; Forceable - string
      :hostname_       ; Forceable - string (clj only)
      :output-fn       ; (fn [data]) -> formatted output string
                       ; (see `default-output-fn` for details)
      :context         ; *context* value at log time (see `with-context`)

      **NB** - any keys not specifically documented here should be
      considered private / subject to change without notice.

  MIDDLEWARE
    Middleware are simple (fn [data]) -> ?data fns (applied left->right) that
    transform the data map dispatched to appender fns. If any middleware
    returns nil, NO dispatch will occur (i.e. the event will be filtered).

  The `example-config` source code contains further settings and details.
  See also `set-config!`, `merge-config!`, `set-level!`."

  {:level :debug  ; e/o #{:trace :debug :info :warn :error :fatal :report}

   ;; Control log filtering by namespaces/patterns. Useful for turning off
   ;; logging in noisy libraries, etc.:
   :ns-whitelist  [] #_["my-app.foo-ns"]
   :ns-blacklist  [] #_["taoensso.*"]

   :middleware [] ; (fns [data]) -> ?data, applied left->right

   :timestamp-opts default-timestamp-opts ; {:pattern _ :locale _ :timezone _}

   :output-fn default-output-fn ; (fn [data]) -> string

   :appenders
   {;; The standard println appender:
    ;; :println (println-appender {:stream :auto})

    :an-example-custom-println-appender
    ;; Inline appender definition (just a map):
    {:enabled?   true
     :async?     false
     :min-level  nil
     :rate-limit [[1 250] [10 5000]] ; 1/250ms, 10/5s
     :output-fn  :inherit
     :fn ; Appender's (fn [data]) -> side effects
     (fn [data]
       (let [{:keys [output_]} data
             formatted-output-str (force output_)]
         (println formatted-output-str)))}}})
```

A few things to note:

 * Appenders are _trivial_ to write & configure - **they're just fns**. It's Timbre's job to dispatch useful args to appenders when appropriate, it's their job to do something interesting with them.
 * Being 'just fns', appenders have basically limitless potential: write to your database, send a message over the network, check some other state (e.g. environment config) before making a choice, etc.

#### Log levels and ns filters

The **log level** may be set:

 * At compile-time: (`TIMBRE_LEVEL` environment variable).
 * Statically using: `timbre/set-level!`/`timbre/merge-level!`.
 * Dynamically using: `timbre/with-level`.

The **ns filters** may be set:

 * At compile-time: (`TIMBRE_NS_WHITELIST`, `TIMBRE_NS_BLACKLIST` env vars).
 * Statically using: `timbre/set-config!`/`timbre/merge-config!`.
 * Dynamically using: `timbre/with-config`.

There are also variants of the core logging macros that take an **explicit config arg**:

```clojure
(timbre/log*  <config-map> <level> <& args>) ; or
(timbre/logf* <config-map> <level> <& args>)
```

Logging calls excluded by a compile-time option (e.g. during Cljs compilation) will be **entirely elided from your codebase**, e.g.:

```bash
#!/bin/bash

# edn values welcome:
export TIMBRE_LEVEL=':warn'               # Elide all lower logging calls
export TIMBRE_NS_WHITELIST='["my-app.*"]' # Elide all other ns logging calls
export TIMBRE_NS_BLACKLIST='["my-app.foo" "my-app.bar.*"]'

lein cljsbuild once # Compile js with appropriate logging calls excluded
lein uberjar        # Compile jar ''
```

### Built-in appenders

#### Basic file appender

```clojure
;; (:require [taoensso.timbre.appenders.core :as appenders]) ; Add to ns

(timbre/merge-config!
  {:appenders {:spit (appenders/spit-appender {:fname "/path/my-file.log"})}})

;; (timbre/merge-config! {:appenders {:spit {:enabled? false}}} ; To disable
;; (timbre/merge-config! {:appenders {:spit nil}}               ; To remove entirely
```

#### Redis ([Carmine]) appender (v3+)

```clojure
;; [com.taoensso/carmine <latest-version>] ; Add to project.clj deps
;; (:require [taoensso.timbre.appenders (carmine :as car-appender)]) ; Add to ns

(timbre/merge-config! {:appenders {:carmine (car-appender/carmine-appender)}})
```

This gives us a high-performance Redis appender:

 * **All raw logging args are preserved** in serialized form (even errors).
 * Configurable number of entries to keep per log level.
 * Only the most recent instance of each **unique entry** is kept.
 * Resulting **log is just a Clojure value**: a vector of log entries (maps).

Clojure has a rich selection of built-in and 3rd party tools for querying values like this.

See also `car-appender/query-entries`.

#### Email ([Postal]) appender

```clojure
;; [com.draines/postal <latest-version>] ; Add to project.clj deps
;; (:require [taoensso.timbre.appenders (postal :as postal-appender)]) ; Add to ns

(timbre/merge-config!
  {:appenders
   {:postal
    (postal-appender/postal-appender
      ^{:host "mail.isp.net" :user "jsmith" :pass "sekrat!!1"}
      {:from "me@draines.com" :to "foo@example.com"})}})
```

#### Other included appenders

A number of 3rd-party appenders are included out-the-box [here](https://github.com/ptaoussanis/timbre/tree/master/src/taoensso/timbre/appenders/3rd_party). **Please see the relevant docstring for details**. Thanks goes to the respective authors! 

Just give me a shout if you've got an appender you'd like to have added.

## Profiling

As of v4.6.0, Timbre's profiling features have been enhanced and exported to a dedicated profiling library called [Tufte].

Timbre's old profiling features **will be kept for backwards compatibility** throughout v4.x, but future development will be focused exclusively on Tufte.

Tufte has out-the-box support for integration with Timbre, and [migration](https://github.com/ptaoussanis/tufte#how-does-tufte-compare-to-the-profiling-in-timbre) is usually simple.

Sorry for the inconvenience!

## This project supports the ![ClojureWerkz-logo] goals

[ClojureWerkz] is a growing collection of open-source, **batteries-included Clojure libraries** that emphasise modern targets, great documentation, and thorough testing.

## Contacting me / contributions

Please use the project's [GitHub issues page] for all questions, ideas, etc. **Pull requests welcome**. See the project's [GitHub contributors page] for a list of contributors.

Otherwise, you can reach me at [Taoensso.com]. Happy hacking!

\- [Peter Taoussanis]

## License

Distributed under the [EPL v1.0] \(same as Clojure).  
Copyright &copy; 2015-2020 [Peter Taoussanis].

<!--- Standard links -->
[Taoensso.com]: https://www.taoensso.com
[Peter Taoussanis]: https://www.taoensso.com
[@ptaoussanis]: https://www.taoensso.com
[More by @ptaoussanis]: https://www.taoensso.com
[Break Version]: https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md

<!--- Standard links (repo specific) -->
[CHANGELOG]: https://github.com/ptaoussanis/timbre/releases
[API]: http://ptaoussanis.github.io/timbre/
[GitHub issues page]: https://github.com/ptaoussanis/timbre/issues
[GitHub contributors page]: https://github.com/ptaoussanis/timbre/graphs/contributors
[EPL v1.0]: https://raw.githubusercontent.com/ptaoussanis/timbre/master/LICENSE
[Hero]: https://raw.githubusercontent.com/ptaoussanis/timbre/master/hero.png "Title"

<!--- Unique links -->
[logging profiler]: #profiling
[Logs as Clojure values]: #redis-carmine-appender-v3
[@palletops/log-config]: https://github.com/palletops/log-config
[@fzakaria/slf4j-timbre]: https://github.com/fzakaria/slf4j-timbre
[io.aviso.exception]: https://github.com/AvisoNovate/pretty
[Carmine]: https://github.com/ptaoussanis/carmine
[Tufte]: https://github.com/ptaoussanis/tufte
[Postal]: https://github.com/drewr/postal
[ClojureWerkz-logo]: https://raw.github.com/clojurewerkz/clojurewerkz.org/master/assets/images/logos/clojurewerkz_long_h_50.png
[ClojureWerkz]: http://clojurewerkz.org/
