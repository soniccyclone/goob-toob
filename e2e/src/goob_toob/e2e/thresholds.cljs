(ns goob-toob.e2e.thresholds)

(def T
  {:start-within      5000
   :min-advance       4.0
   :park-drift        1.0
   :park-live-within  7000
   :park-track        5.5
   :sync-within       8000
   :settled           0.5
   :steady-p95        0.5
   :pause-agreement   0.3
   :catch-rate        [1.04 1.06]
   :catch-within      2000
   :nudge-rate        [0.94 0.96]
   :room-slow-rate    0.97
   :room-slow-within  6000
   :closing-min       0.05
   :pullback-within   3000
   :rate-agreement    0.01
   :resync-within     15000
   :chat-within       2000
   :control-hold      2000
   :control-within    2000
   :transcode-within  90000
   :still-playing     1.0
   :reconnect-seeks   1
   :pullback-seekbacks 1
   :autostart-within  6000})

(def per-engine {})

(defn t [engine k]
  (get-in per-engine [engine k] (get T k)))

(def W
  {:clip-seconds      90
   :upload-seconds    180
   :sample-every      250
   :host-window       6000
   :host-head-start   5000
   :join-window       20000
   :resume-window     10000
   :seek-jump         30.0
   :laggard-inject    -3.5
   :laggard-window    30000
   :early-inject      3.0
   :early-window      10000
   :nudge-inject      1.0
   :nudge-window      25000
   :throttle-kbps     1500
   :throttle-window   40000
   :still-window      10000
   :offline-for       6000
   :offline-seek      20.0
   :late-head-start   8000
   :parked-window     12000
   :chat-fill         20
   :gesture-retry     1000
   :ready-timeout     30000
   :autostart-window  12000
   :control-idle      5000
   :pong-every        5000
   :parked-hold       6000})

