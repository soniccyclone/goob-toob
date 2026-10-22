(ns goob-toob.e2e.config
  (:require ["@playwright/test" :refer [defineConfig]]))

(def port (or (.. js/process -env -GOOB_E2E_PORT) "18080"))
(def ci? (some? (.. js/process -env -CI)))

(def config
  (defineConfig
    (clj->js
     {:testDir "."
      :testMatch ["**/*.js"]
      :testIgnore ["**/config.js" "**/setup.js"]
      :timeout 120000 :fullyParallel false :workers 1 :retries (if ci? 1 0)
      :outputDir "../test-results"
      :reporter [["list"] ["html" {:open "never" :outputFolder "../report"}]]
      :globalSetup "./setup.js"
      :use {:baseURL (str "http://127.0.0.1:" port)
            :trace {:mode "retain-on-failure" :snapshots false :screenshots true}
            :video "retain-on-failure"}
      :projects [{:name "chromium" :use {:browserName "chromium"
                                         :launchOptions {:args ["--autoplay-policy=document-user-activation-required"]}}}
                 {:name "chromium-lenient" :use {:browserName "chromium"
                                                 :launchOptions {:args ["--autoplay-policy=no-user-gesture-required"]}}}
                 {:name "firefox"  :use {:browserName "firefox"
                                         :launchOptions {:firefoxUserPrefs {"media.autoplay.default" 1}}}}
                 {:name "firefox-lenient" :use {:browserName "firefox"
                                                :launchOptions {:firefoxUserPrefs {"media.autoplay.default" 0}}}}
                 {:name "webkit"   :use {:browserName "webkit"}}]
      :webServer {:command "clojure -M:dev -m goob-toob.core"
                  :cwd "../.." :url (str "http://127.0.0.1:" port "/api/me")
                  :env {:STORAGE_DIR (.. js/process -env -GOOB_E2E_STORAGE)
                        :ADMIN_PASSWORD "pw" :PORT port}
                  :stdout "pipe" :stderr "pipe"
                  :timeout 120000 :reuseExistingServer (not ci?)}})))
