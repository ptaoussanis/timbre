> This project uses [Break Versioning](https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md) as of **Aug 16, 2014**.

## v5.0.0-RC1 / 2020 Sep 14

```clojure
[com.taoensso/timbre "5.0.0-RC1"]
```

> This is a **major feature release**. It should be non-breaking for most users, but **please test**!  
> See [here](https://github.com/ptaoussanis/encore#recommended-steps-after-any-significant-dependency-update) for recommended steps when updating any Clojure/Script dependencies.

### Changes since `v4.10.0`

- **[BREAKING]** Bump minimum Clojure `1.5`->`1.7`
- **[BREAKING]** [#155] Change default timestamp pattern from `yy-MM-dd HH:mm:ss` to `ISO8601`
- **[Deprecated]** `:ns-whitelist` and `:ns-blacklist` options are being replaced with a single `:ns-filter` option. See [docstring](http://ptaoussanis.github.io/timbre/taoensso.timbre.html#var-default-config) for details. 
- **[Deprecated]** `:level` config option is being renamed `:min-level`
- [#289] [3rd-party appenders] Logstash appender: now async by default
- [#290] [3rd-party appenders] Logstash appender: don't use ANSI colors in stacktraces (@antonmos)
- [#288] [Implementation] Switch from `.cljx` to `.cljc` (@anthonygalea)

### New since `v4.10.0`

- [#255] In additional to the usual values like `:trace`, `:warn`, etc. - min levels may now also be of form `[[<ns-pattern> <min-level>] ...]` (both in global and per-appender config). See [docstring](http://ptaoussanis.github.io/timbre/taoensso.timbre.html#var-default-config) for details (@mikekap, @ptaoussanis).
- [#73 #301] [3rd-party appenders] Add Syslog appender (@audriu)
- [#270] [3rd-party appenders] Add UDP appender (@inaimathi)
- [#266 #239] Add support for timestamps in Cljs (@thatismatt)
- [#271] Appender data now incl. `:spying?` key
- [#265] Officially document `^:meta` feature (was previously experimental)
  - Enables ^:meta {:raw-console? true} ClojureScript console appender option
- New JVM properties and env variables to control compile-time elision, see [docstring](http://ptaoussanis.github.io/timbre/taoensso.timbre.html#var-*config*) for details
- Improved config documentation

### Fixes since `v4.10.0`

- [#296 #295] Fix Nodejs stacktraces (@nenadalm)
- [#250] Mod default cljs appenders under Nodejs (@sundbp)
- [#251 #292] `spit-appender`: add locking for thread safety
- [#257] Println appender hotfix: use `:error-level?` instead of `:error?` (@rinx)
- [#303] Make `get-hostname` more robust to exceptions
- [#292] Always honour system newline
- Carmine appender: stop using deprecated Nippy API
- [#285 #282] [3rd-party appenders] Fix some bugs (@borkdude)
- [#233] [3rd-party appenders] Gelf: ensure `short_message` is not empty + add extra fields (@vise890)
- [#246] [3rd-party appenders] Newrelic: fix ns typo (@jafingerhut)


## v4.10.0 / 2017 Apr 14

```clojure
[com.taoensso/timbre "4.10.0"]
```

> This is a **feature and maintenance release** that should be non-breaking

* [#215] **New**: Add 3rd-party appender for Sentry (@samuelotter)
* [#199] **Fix**: Unintended warning output to cljs for compile-time elision
* [#218] **Fix**: Nullary version of `timbre.tools.logging/use-timbre`
* [#216] **Fix**: Cljs console appender: don't log superfluous 'null' for raw output (@michaelcameron)

## v4.8.0 / 2016 Dec 18

```clojure
[com.taoensso/timbre "4.8.0"]
```

> This is a **major feature & maintenance release** that should be non-breaking in most cases (see 1 exception below)

* **BREAKING**: Middleware no longer receives :msg_ or :hash_ (was rarely useful, caused confusion)
* **DEPRECATED**: Per-appender :middleware-fn (was rarely useful, caused confusion)

* [#198] **New**: Add 3rd-party kafka appender (@gfZeng)
* [#202] **New**: Spit appender: add `:append?` option (@tkocmathla)
* [#195] **New**: Logstash appender: add `:flush?` option (@tvanhens)

* [#192] **Impl**: Rolling appender: create dirs when they don't exist (@dsapala)
* [#207] **Impl**: Add docstring for `with-context`
* **Impl**: Default output fn now falls back to `?file` when `?ns-str` unavailable
* **Impl**: Improve error message for logging calls with missing format pattern

* [#207] **Fix**: Middleware couldn't influence automatic msg_ generation
* [#199] **Fix**: Unintended elision warning output to cljs
* [#206] **Fix**: Resolve `slf4j-timbre` issue with `may-log?` and namespace filtering

## v4.7.4 / 2016 Aug 23

```clojure
[com.taoensso/timbre "4.7.4"]
```

> This is a **minor hotfix release**

* **Hotfix**: [#188] Regression re: interleaving println appender
* **Hotfix**: [#185] 3rd-party logstash appender deps issue (@robingl)

## v4.7.0 / 2016 Jul 19

```clojure
[com.taoensso/timbre "4.7.0"]
```

* **New**: [#183] Add support for appender-level middleware

## v4.6.0 / 2016 Jul 12

```clojure
[com.taoensso/timbre "4.6.0"]
```

> Non-breaking, **minor feature release**

* **New**:  [#176] Add New Relic appender (@polymeris)
* **Impl**: [#177] Improvements to clojure.tools.logging integration (@MerelyAPseudonym)
* **Impl**: [#179] Break hostname util into smaller components
* **Impl**: [#174] Smarter (faster) spit appender path creation
* **Impl**: Revert recent profiling changes, restore ^:dynamic (multi-threaded) behaviour

## v4.5.1 / 2016 Jun 29

```clojure
[com.taoensso/timbre "4.5.1"]
```

> This is a **minor hotfix release**

* **Hotfix**: address an issue for AOT/slf4j-timbre users.
* **Hotfix**: missing type hint during timestamp generation.

## v4.5.0 / 2016 Jun 26

```clojure
[com.taoensso/timbre "4.5.0"]
```

> This is a **major, non-breaking release** focused on refactoring and performance (esp. profiling performance)

* **BREAKING** (rarely): ids given to `timbre.profiling/pspy` and `timbre.profiling/profile` must now always be compile-time consts (e.g. keywords).
* **DEPRECATED**: Appender args - `:?err_`, `:vargs_` (delays).
* **New**: Appender args - `:?err`, `:vargs`, `:output_`.
* **New**: Allow disabling ANSI colours with env var [#172 @ccfontes].
* **Impl**: Minor logging perf improvements.
* **Impl**: *Major* profiling perf improvements.

## v4.4.0 / 2016 Jun 10

```clojure
[com.taoensso/timbre "4.4.0"]
```

> This is a **major, non-breaking release**, enjoy :-)

* **New**: Add support for appender-level ns filters [#171]
* **New**: Add 3rd-party logstash appender [#166 @dfrese]
* **New**: Add PostgreSQL appender [#160 @yuliu-mdsol]
* **New**: Add Slack appender [#159 @sbelak]
* **Fix**: Make rotor appender thread-safe [#168 @mikesperber]
* **Fix**: Don't cache cljs console appender's `js/console` [#165]
* **Fix**: Fix surprising `merge-config` nil behaviour [#163]

## v4.3.1 / 2016 Feb 28

* **Hotfix**: had a removed var in the profiling macro

```clojure
[com.taoensso/timbre "4.3.1"]
```

## v4.3.0 / 2016 Feb 26

> This is a major, non-breaking feature release

* **New**: added 3rd-party gelf appender [#147 @davewo]
* **New**: new `:?hash-arg` data key for use by custom data hash fns
* **New**: low-level `log!` macro for use in tooling (slf4j-timbre, etc.)
* **New**: allow compile-time log level to be set with system property [#151 @DomKM]
* **New**: ClojureScript console logger docstring now incl. instructions for Chrome Blackboxing [#132 @danskarda]
* **New**: include line numbers in default output for non-nested macros [#132]
* **Impln**: appenders no longer need to worry about using `force` instead of `@`/`deref`

```clojure
[com.taoensso/timbre "4.3.0"]
```

## v4.2.1 / 2016 Jan 14

> This is a non-breaking hotfix release

* **Fix**: compile issue with Clojure 1.6 [#146 @nicferrier]

```clojure
[com.taoensso/timbre "4.2.1"]
```

## v4.2.0 / 2015 Dec 27

> This is a non-breaking feature release

* **Change**: switch default timestamp timezone from JVM default to UTC [#135]
* **Change**: switch default timestamp pattern from `yy-MMM-dd` -> `yy-MM-dd pattern` (easier to sort) [#135]
* **New**: `swap-config!` now supports &args [#137 @rsslldnphy]
* **New**: rotor appender now creates necessary paths [#140 @dsapala]
* **New**: faster (transducer-based) string joins with Clojure 1.7+ [#133]
* **New**: records now get a human-readable string representation [#133]
* **Fix**: application slowdown due to agents shutdown [#141 @ryfow]

```clojure
[com.taoensso/timbre "4.2.0"]
```


## v4.1.5 / 2015 Dec 27

> This is a non-breaking hotfix release

* Assist fix of https://github.com/fzakaria/slf4j-timbre/issues/8

```clojure
[com.taoensso/timbre "4.1.5"]
```


## v4.1.4 / 2015 Sep 30

> This is a non-breaking hotfix release

* Fix broken support for Clojure 1.5

```clojure
[com.taoensso/timbre "4.1.4"]
```


## v4.1.2 / 2015 Sep 26

> This is a non-breaking hotfix release

* Bring back deprecated `logp` macro from Timbre v3.x to ease back-compatibility [#67]

```clojure
[com.taoensso/timbre "4.1.2"]
```


## v4.1.1 / 2015 Aug 16

> This is a non-breaking hotfix release

* **Fix**: shutdown-agents shutdown hook can interfere with other important hooks [#127 @hadronzoo]

```clojure
[com.taoensso/timbre "4.1.1"]
```


## v4.1.0 / 2015 Aug 7

> This is a non-breaking feature release

* **DEPRECATED**: `*context*` val is now located under a `:context` key in appender's data map [#116 @mikesperber]
* **New**: Added `profiling/fnp` macro

```clojure
[com.taoensso/timbre "4.1.0"]
```


## v4.0.2 / 2015 June 26

> This is a minor, non-breaking bug fix release

* **Fix**: broken v4 3rd-party appender: rotor [#105 #107 @yogthos]
* **Fix**: broken tools.logging support [#110 @Guthur]

```clojure
[com.taoensso/timbre "4.0.2"]
```


## v4.0.1 / 2015 June 13

> This is a minor, non-breaking feature release

* **New**: add `get-env`, `log-env` macros [#103 @RickMoynihan]

```clojure
[com.taoensso/timbre "4.0.1"]
```


## v4.0.0 / 2015 June 10

> This is a **MAJOR** update. Your custom appenders **WILL BREAK**. Your configuration **MIGHT BREAK**. Your call sites should be fine. I've updated all bundled appenders, but **haven't tested** any 3rd-party appenders.

* **New**: full **ClojureScript** support, including a default js/console appender [#51]
* **New**: support for compile-time ns filtering + elision (both Clj+Cljs)
* **New**: support for MDC-like contexts [#42]
* **New**: default :println appender has picked up a :stream opt [#49]
* **New**: create necessary spit appender paths [#93]
* **New**: full-power fn-level `log1-fn` util [#99]
* **New**: added a reference appender example [here](https://github.com/ptaoussanis/timbre/blob/master/src/taoensso/timbre/appenders/core.cljx)
* **Implementation**: modernized + simplified codebase
* **Implementation**: significant performance improvements across the board
* **Implementation**: use delays to avoid unnecessarily producing unused arg msgs [#71]
* **Fix**: auto shutdown agents to prevent slow app shutdown [#61]

```clojure
[com.taoensso/timbre "4.0.0"]
```

### Migration checklist

* Removed vars: `timbre/config`, `timbre/level-atom`, `default-fmt-output-fn`
* The fn signature for `set-config!` has changed: `[ks val]` -> `[config]`
* Middleware now apply left->right, not right->left
* Renamed a default appender: `:standard-out` -> `:println`
* Renamed config opts: `:timestamp-pattern`, `:timestamp-locale` -> `:timestamp-opts {:pattern _ :locale _ :timezone _}`
* Renamed config opts: `:whitelist` -> `:ns-whitelist`, `:blacklist` -> `:ns-blacklist`
* Appender :rate-limit format has changed: `[ncalls ms]` -> `[[ncalls ms] <...>]`
* Renamed appender args: `:ns`->`:?ns-str`, `:file`->`:?file`, `:line`->`:?line`
* Appender args now wrapped with delays: `:throwable`->`:?err_`, `:message`->`:msg_`, `:timestamp`->`:timestamp_`, `:hostname`->`:hostname_`, `:args`->`:vargs_`
* Appender args removed: `:output`, `:ap-config`
* Appender args added: `:output-fn (fn [data]) -> string`
* `stacktrace` util fn signature changed: `[throwable & [sep fonts]` -> `[err & [opts]]`
* All bundled 3rd-party appenders have moved to a new `3rd-party` ns.
* Bundled 3rd-party appender constructor signatures _may_ have changed, please double check.

Apologies for the hassle in migrating. The changes made here all bring serious benefits (performance, simplicity, future extensibility, cross-platform support) and I'm confident that v4's the last time I'll need to touch the core design. Future work will be focused on polish, stability, and better+more bundled appenders.

/ Peter Taoussanis

## v3.4.0 / 2015 Feb 16

 > This should be a **non-breaking** release that only bumps some old dependencies.


## v3.3.1 / 2014 Sep 7

 * **FIX** https://github.com/ptaoussanis/timbre/issues/79.


## v3.3.0 / 2014 May 8

 * **CHANGE**: Update IRC appender to Timbre v3 style (@crisptrutski).
 * **FIX** [#47]: correctly format nanosecond profiling times.
 * **FIX** [#77]: profile ids now use correct (compile-time rather than runtime) ns prefix.
 * **NEW**: Add zmq appender (@angusiguess).
 * **NEW** [#75]: Make defnp support multi-arity functions (@maurolopes)


## v3.2.1 / 2014 May 7

 * **FIX**: missing tools.reader upstream dependency (@ducky427).


## v3.2.0 / 2014 May 6

 * **FIX** [#60]: `defnp` no longer generates an Eastwood warning (@ducky427).
 * **CHANGE**: Improved profiling memory efficiency (max memory use, was previously unbounded).
 * **CHANGE**: Profiling: make larger call numbers easier to read.
 * [#63]: **NEW**: Add support for thread-local configuration (@jameswarren).


## v3.1.6 / 2014 Mar 16

 * **FIX** [#56]: `defnp`/`p` head retention issue (@kyptin).


## v3.1.5 / 2014 Mar 15

 * **FIX**: `profiling/p*` was defined incorrectly (@kyptin).


## v3.1.4 / 2014 Mar 13

 * **NEW**: Add `profiling/p*` macro.
 * **CHANGE**: Include `p`, `p*` in `refer-timbre` imports.
 * **FIX**: rotor appender not rotating (@iantruslove, @kurtharriger).


## v3.1.3 / 2014 Mar 11

 * FIX: profiling id namespacing.


## v3.1.1 / 2014 Feb 26

 * FIX: project.clj to prevent unnecessary downstream deps.


## v3.1.0 / 2014 Feb 23

### New

 * #47 Added `taoensso.timbre.profiling/pspy*` fn.

### Changes

 * Made Carmine appender resistant to unexpected log entry thaw errors.
 * Moved most utils to external `encore` dependency.

### Fixes

 * #50 Fixed rotor appender so that it respects :fmt-output-opts (kenrestivo).


## v3.0.0 / 2014 Jan 30

> Major update, non-breaking though users with custom appenders are encouraged to view the _Changes_ section below. This version polishes up the codebase and general design. Tightened up a few aspects of how appenders and appender middleware work. Added a serializing Carmine appender (I use something similar in prod most of the time). Also finally added facilities for ad hoc (non-atom) logging configuration.
>
> Overall quite happy with the state of Timbre as of this release. No major anticipated improvements/changes from here (modulo bugs).

### New

 * Android appender, courtesy of AdamClements.
 * Rolling appender, courtesy of megayu.
 * Powerful, high-performance Carmine (Redis) appender: query-able, rotating serialized log entries by log level. See README or appender's docstring for details. (Recommended!)
 * Appender rate limits now specified in a more flexible format: `[ncalls window-msecs]`, e.g. `[1 2000]` for 1 write / 2000 msecs.
 * Appender rate limits now also apply (at 1/4 ncalls) to any _particular_ logging arguments in the same time window. This helps prevent a particular logging call from flooding the limiter and preventing other calls from getting through.
 * `sometimes` macro that executes body with given probability. Useful for sampled logging (e.g. email a report for 0.01% of user logins in production).
 * `log` and `logf` macros now take an optional logging config map as their first argument: `(log :info "hello") => use @timbre/config`, `(log <config> :info "hello") => use <config>`.
 * Appenders can now specify an optional `:fmt-output-opts` that'll get passed to `fmt-output-fn` for any special formatting requirements they may have (e.g. the Postal email appender provides an arg to suppress ANSI colors in stacktrace output).

### Changes

 * **EXPERIMENTAL**: stacktraces now formatted with `io.aviso/pretty` rather than clj-stacktrace. Feedback on this (esp. coloring) welcome!
 * **DEPRECATED**: `red`, `green`, `blue` -> use `color-str` instead.
 * **DEPRECATED**: config `prefix-fn` has been replaced by the more flexible `fmt-output-fn`. Change is backwards compatible.
 * **REMOVED**: Per-appender `:prefix` option dropped - was unnecessary. If an appender wants custom output formatting, it can do so w/o using an in-config formatter.
 * Update `refer-timbre` (add profiling, logf variations, etc.).
 * **DEPRECATED**: atom logging level is now located in `level-atom` rather than `config`. Old in-config levels will be respected (i.e. change is backwards compatible).
 * **DEPRECATED**: appender rate limits are now specified as `:rate-limit [ncalls window-msecs]` rather than `:limit-per-msecs ncalls`. Change is backwards compatible.
 * Built-in appenders have been simplified using the new `default-output` appender arg.
 * Postal appender now generates a more useful subject in most cases.

### Fixes

 * #38 Broken namespace filter (mlb-).
 * Messages are now generated _after_ middleware has been applied, allowing better filtering performance and more intuitive behaviour (e.g. changes to args in middleware will now automatically percolate to message content).
 * `(logf <level> "hello %s")` was throwing due to lack of formatting args.


## v2.6.3 → v2.7.1
  * Core: `getHostName` no longer runs on the main thread for better Android compatibility (AdamClements).
  * Profiling: added `defnp` macro.
  * Profiling: fix compile-time name creation: now runtime (aperiodic).
  * Appenders: added rotating file appender (mopemope).


## v2.5.0 → v2.6.3
  * Perf: add support for a compile-time logging level environment variable (`TIMBRE_LOG_LEVEL`). See `timbre/compile-time-level` docstring for details.
  * Fix: `use-timbre`, `str-println` bugs.
  * Fix: Null Pointer Exception in clj-stacktrace (bitemyapp).


## v2.4.1 → v2.5.0
  * Added `:file` and `:line` appender args.
  * Fixed `make-timestamp-fn` thread safety.


## v2.3.4 → v2.4.1
  * Added `refer-timbre` for conveniently `require`ing standard timbre vars.
  * Postal appender now uses throwable as subject when no other args given.
  * `log-errors`, `log-and-rethrow-errors` now catch Throwable instead of Exception.


## v2.2.0 → v2.3.4
  * Added `with-log-level` for thread-local logging levels: `(with-level :trace (trace "This will log!"))`. Esp. useful for developing & unit tests, etc.


## v2.1.2 → v2.2.0
  * Add socket, MongoDB appenders (thanks to emlyn).


## v2.0.0 → v2.1.2
  * Added appenders: socket, IRC, MongoDB (CongoMongo). See [README](https://github.com/ptaoussanis/timbre#built-in-appenders) for details.
  * Add `ex-data` output to `stacktrace` fn.
  * Fixed a number of small bugs (mostly regressions from 1.x).


## v1.6.0 → v2.0.0
  * Refactor for integration with tools.logging.
  * **BREAKING**: Drop Clojure 1.3 support.
  * **DEPRECATED**: `:max-messages-per-msecs` appender arg -> `:limit-per-msecs`.

  * **BREAKING**: `:more` appender arg has been dropped. `:message` arg is now a string of all arguments as joined by `logp`/`logf`. Appenders that need unjoined logging arguments (i.e. raw arguments as given to `logp`/`logf`) should use the new `:log-args` vector.

  * **BREAKING**: Stacktraces are no longer automatically generated at the `log`-macro level. Stacktraces are now left as an appender implementation detail. A `:throwable` appender argument has been added along with a `stacktrace` fn.
