(ns goob-toob.e2e.journeys.audio
  "The viewer's volume boost (#56): a Web Audio gain past the stream's own
  100 %. Headless cannot hear anything, so the probe reports the GainNode's
  value and the element's own volume, and that is what these judge."
  (:refer-clojure :exclude [test])
  (:require ["@playwright/test" :refer [test expect]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goob-toob.e2e.api :as api]
            [goob-toob.e2e.async :refer-macros [go-try <?]]
            [goob-toob.e2e.journey :refer [defjourney]]
            [goob-toob.e2e.metrics :as m]
            [goob-toob.e2e.party :as party]
            [goob-toob.e2e.thresholds :refer [t T W]]))

(def ^:private chain-up "goobProbe().gain !== null")

(defn- gain-near [g]
  ;; AudioParam values are float32: 1.05 reads back as 1.0499999523...
  (str "Math.abs(goobProbe().gain - " g ") < 0.001"))

(defn- chain!
  "A real click (the chat input) is the gesture the chain is built on; a
  lenient browser's autostart gave the page none. Returns ms until the
  probe reports a gain."
  [^js page]
  (go-try
    (let [t0 (js/Date.now)]
      (<p! (party/touch! page))
      (<p! (party/wait-for! page chain-up (:start-within T)))
      (- (js/Date.now) t0))))

(defn- slide!
  "The slider dragged to pct: its value set and an input event, as a drag
  fires."
  [^js page pct]
  (.evaluate page (str "(() => { const s = document.querySelector('input.vol');"
                       " s.value = " pct "; s.dispatchEvent(new Event('input')); })()")))

(defn- control [^js page]
  (go-try
    (js->clj (<p! (.evaluate page (str "(() => ({slider: document.querySelector('input.vol').value,"
                                       " label: document.querySelector('.vol-pct').textContent,"
                                       " stored: localStorage.getItem('goob-volume')}))()")))
             :keywordize-keys true)))

(defjourney "the volume slider sets the gain and a reload remembers it" [{:keys [browser browser-name base-url]} info]
  (let [fx (api/fixture info)
        {:keys [room host]} (<? (party/host! browser base-url browser-name fx))
        ^js page (:page host)
        chain-ms (<? (chain! page))
        native (<? (party/probe page))
        _ (<p! (slide! page 200))
        boosted (<? (party/probe page))
        ui-200 (<? (control page))
        _ (<p! (.reload page))
        _ (<p! (party/wait-for! page "window.goobProbe && goobProbe().expected !== null" (:ready-timeout W)))
        _ (<? (party/join! page (t browser-name :start-within)))
        rechain-ms (<? (chain! page))
        restored (<? (party/probe page))
        ui-restored (<? (control page))
        _ (<p! (.dblclick (.locator page "input.vol")))
        _ (<p! (party/wait-for! page (gain-near 1.0) (t browser-name :chat-within)))
        reset (<? (party/probe page))
        series (<? (m/sample page (:host-window W)))
        summary (m/summarize series 0)]
    (<p! (m/record! info summary series {:chain-ms chain-ms :rechain-ms rechain-ms
                                         :native-gain (:gain native) :boosted-gain (:gain boosted)
                                         :restored-gain (:gain restored) :reset-gain (:gain reset)
                                         :volume (:volume restored) :muted (:muted restored)
                                         :stored (:stored ui-restored)}))
    (-> (expect (:gain native) "gain at native") (.toBe 1))
    (-> (expect (:gain boosted) "gain with the slider at 200 %") (.toBe 2))
    (-> (expect (:label ui-200) "label at 200 %") (.toBe "200 %"))
    (-> (expect (:gain restored) "gain after the reload") (.toBe 2))
    (-> (expect (:slider ui-restored) "slider after the reload") (.toBe "200"))
    (-> (expect (:stored ui-restored) "localStorage goob-volume") (.toBe "200"))
    (-> (expect (:volume restored) "element volume stays 1") (.toBe 1))
    (-> (expect (:muted restored) "element not muted") (.toBe false))
    (-> (expect (:gain reset) "gain after the double-click") (.toBe 1))))

(defjourney "ArrowUp and ArrowDown step the volume, not the element" [{:keys [browser browser-name base-url]} info]
  ;; Chromium's native controls take ArrowUp/Down as element volume at the
  ;; focused <video> (ArrowDown would drop it to 0.9); the capture-phase
  ;; handler must win, and an input keeps its own keys.
  (let [fx (api/fixture info)
        {:keys [host]} (<? (party/host! browser base-url browser-name fx))
        ^js page (:page host)
        chain-ms (<? (chain! page))
        video (.locator page "video")
        within (t browser-name :chat-within)
        _ (<p! (slide! page 100))
        _ (<p! (.focus video))
        _ (<p! (.press (.-keyboard page) "ArrowUp"))
        up? (try (<p! (party/wait-for! page (gain-near 1.05) within)) true
                 (catch :default _ false))
        up (<? (party/probe page))
        up-ui (<? (control page))
        _ (<p! (.press (.-keyboard page) "ArrowDown"))
        down? (try (<p! (party/wait-for! page (gain-near 1.0) within)) true
                   (catch :default _ false))
        down (<? (party/probe page))
        _ (<p! (.focus (.getByPlaceholder page "say something gooey…")))
        _ (<p! (.press (.-keyboard page) "ArrowUp"))
        _ (<p! (.waitForTimeout page 300))
        typing (<? (party/probe page))]
    (<p! (m/record! info {} [] {:chain-ms chain-ms :up up? :up-gain (:gain up) :up-volume (:volume up)
                                :down down? :down-gain (:gain down) :down-volume (:volume down)
                                :typing-gain (:gain typing)}))
    (-> (expect up? "ArrowUp from 100 % reaches 1.05") (.toBe true))
    (-> (expect (:gain up) "gain after ArrowUp") (.toBeCloseTo 1.05 3))
    (-> (expect (:label up-ui) "label after ArrowUp") (.toBe "105 %"))
    (-> (expect (:volume up) "element volume after ArrowUp") (.toBe 1))
    (-> (expect down? "ArrowDown back to 1.0") (.toBe true))
    (-> (expect (:volume down) "element volume after ArrowDown (native would say 0.9)") (.toBe 1))
    (-> (expect (:gain typing) "ArrowUp in the chat input leaves the gain alone") (.toBe 1))))
