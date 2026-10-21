(ns goob-toob.e2e.async)

(defmacro go-try
  "A go block whose thrown error becomes its value, for <? to rethrow."
  [& body]
  `(cljs.core.async/go
     (try ~@body
          (catch :default e# (unwrap e#)))))

(defmacro <? [ch]
  `(throw-err (cljs.core.async/<! ~ch)))
