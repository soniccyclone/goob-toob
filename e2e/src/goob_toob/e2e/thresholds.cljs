(ns goob-toob.e2e.thresholds
  (:require [goob-toob.sync-core :as sc]))

(def T
  {:start-within      5000
   :min-advance       4.0
   :park-drift        1.0
   :park-live-within  7000
   ;; a parked frame is re-parked on each pong (:pong-every, 5 s) and each
   ;; park lands up to the dead band (1.0 s) off, so a sample between two
   ;; parks can sit a pong plus a landing behind, plus one sample interval
   :park-track        6.5
   :sync-within       8000
   ;; A landing (join, late join, resync, resume) is a seek followed by a
   ;; stall while the player buffers there, and the element comes back
   ;; behind by that stall. The policy leaves anything inside its dead band
   ;; alone (CLAUDE.md, "Sync design: hard rules"), so a landing is in sync
   ;; once it is inside the band, and no gate may ask for less: it would
   ;; wait for a correction that never comes.
   :landed            (:dead-band sc/tuning)
   :settled           0.5
   :steady-p95        0.5
   :pause-agreement   0.3
   :catch-rate        [1.04 1.06]
   :catch-within      2000
   :nudge-rate        [0.94 0.96]
   :room-slow-rate    0.97
   :room-slow-within  6000
   :closing-min       0.05
   :rate-agreement    0.01
   :resync-within     15000
   :chat-within       2000
   :control-hold      2000
   :control-within    2000
   :transcode-within  90000
   :still-playing     1.0
   :reconnect-seeks   1
   :autostart-within  6000
   ;; playlists: an item switch is an `item` broadcast, a source swap and
   ;; the first-load start path again, so a switch costs the landings'
   ;; seeks and no more: a park on the item state, a park on loadeddata,
   ;; the autostart's begin! (3 recorded in every run), plus one spare for
   ;; a pong's re-park falling inside the window
   :switch-within     8000
   :advance-slack     3000
   :switch-seeks      4
   :from-zero         2.0})

(def per-engine {})

(defn t [engine k]
  (get-in per-engine [engine k] (get T k)))

(def W
  {:clip-seconds      90
   :upload-seconds    180
   :extra-clip-seconds 60
   :end-lead          5.0
   :advance-window    18000
   :end-window        12000
   :switch-window     8000
   :skip-hold         3000
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

