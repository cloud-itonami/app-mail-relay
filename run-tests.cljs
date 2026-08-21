#!/usr/bin/env nbb
;; ClojureScript 側でも同じテストを回す。
;;
;;   nbb --classpath src:test run-tests.cljs
;;
;; 受信の振り分けは Cloudflare Email Worker（CLJS）で動く。JVM で通ることは
;; その証拠にならないので、両方で回す。
(ns run-tests
  (:require [cljs.test :as t]
            [mail-relay.account-test]
            [mail-relay.api-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (println)
  (println (str "Ran " (:test m) " tests, " (:pass m) " assertions passed, "
                (:fail m) " failures, " (:error m) " errors."))
  (when-not (t/successful? m)
    (js/process.exit 1)))

(t/run-tests 'mail-relay.account-test 'mail-relay.api-test)
