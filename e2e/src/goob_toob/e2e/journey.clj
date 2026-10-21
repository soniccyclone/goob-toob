(ns goob-toob.e2e.journey)

(defmacro defjourney
  "(defjourney \"title\" [{:keys [browser browser-name base-url]} info] body...)
  Registers a Playwright test. Fixtures arrive as a map (:browser
  :browser-name :base-url :request); info is Playwright's TestInfo. The body
  runs in a go block, so <p! awaits promises and <? takes from go-try
  helpers; a thrown error fails the test. Contexts opened through
  goob-toob.e2e.party close when the test ends. The spec refers test from
  @playwright/test and excludes cljs.core/test, so Playwright sees the spec
  file as the caller."
  [title [fx info] & body]
  `(~'test ~title
           (journey-fn
            (fn [fx# info#]
              (let [~fx (fixtures fx#)
                    ~info info#]
                (goob-toob.e2e.async/go-try ~@body nil))))))
