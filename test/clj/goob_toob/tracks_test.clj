(ns goob-toob.tracks-test
  "The viewer's track defaults and labels (plans/2026-10-30-audio-subtitle-
  tracks.org, step 7): per viewer, resolved per item, never room state."
  (:require [clojure.test :refer [deftest is testing]]
            [goob-toob.tracks :as tracks]))

(def nabari
  ;; the test rip's shape: eng 5.1 default, jpn stereo, one ASS sub
  {:audio [{:i 0 :lang "eng" :title "AAC" :channels 6 :default true}
           {:i 1 :lang "jpn" :title nil :channels 2 :default false}]
   :subs [{:lang "eng" :title "ASS" :url "/dash/x/subs-eng.vtt"}]
   :subs-unavailable 0})

(deftest browser-language-maps-to-the-manifest-codes
  (is (= "jpn" (tracks/browser-lang->iso "ja-JP")))
  (is (= "jpn" (tracks/browser-lang->iso "ja")))
  (is (= "eng" (tracks/browser-lang->iso "en-US")))
  (is (= "spa" (tracks/browser-lang->iso "es")))
  (is (= "fre" (tracks/browser-lang->iso "fr-CA")))
  (is (= "ger" (tracks/browser-lang->iso "de")))
  (is (= "por" (tracks/browser-lang->iso "pt_BR")))
  (testing "a three-letter primary subtag is already a code"
    (is (= "fil" (tracks/browser-lang->iso "fil-PH"))))
  (testing "nothing to go on"
    (is (nil? (tracks/browser-lang->iso nil)))
    (is (nil? (tracks/browser-lang->iso "")))
    (is (nil? (tracks/browser-lang->iso "xx")))))

(deftest aliases-cover-both-iso-639-2-forms-and-the-two-letter-code
  (is (= #{"fre" "fra" "fr"} (tracks/lang-aliases "fre")))
  (is (= #{"fre" "fra" "fr"} (tracks/lang-aliases "fr")))
  (is (= #{"eng" "en"} (tracks/lang-aliases "ENG")))
  (is (= #{"und"} (tracks/lang-aliases "und")))
  (is (= #{} (tracks/lang-aliases nil)))
  (is (tracks/same-lang? "eng" "en"))
  (is (tracks/same-lang? "deu" "ger"))
  (is (not (tracks/same-lang? "eng" "jpn")))
  (is (not (tracks/same-lang? "eng" nil)))
  (is (not (tracks/same-lang? "" ""))))

(deftest the-initial-settings-regex-is-exact
  ;; dash.js bcp-47-normalizes a *string* lang (eng -> en) and then fails to
  ;; match the manifest's "eng"; the RegExp path matches the aliases as is
  (is (= "^(en|eng)$" (tracks/lang-regex-source "eng")))
  (is (re-matches (re-pattern (tracks/lang-regex-source "eng")) "eng"))
  (is (re-matches (re-pattern (tracks/lang-regex-source "eng")) "en"))
  (is (nil? (re-matches (re-pattern (tracks/lang-regex-source "eng")) "english")))
  (is (nil? (re-matches (re-pattern (tracks/lang-regex-source "eng")) ""))))

(deftest labels-prefer-the-title-then-the-language-name
  (is (= "AAC" (tracks/lang-label "eng" "AAC")))
  (is (= "Japanese" (tracks/lang-label "jpn" nil)))
  (is (= "Japanese" (tracks/lang-label "jpn" "  ")))
  (is (= "English" (tracks/lang-label "eng")))
  (is (= "French" (tracks/lang-label "fra")))
  (is (= "German" (tracks/lang-label "deu")))
  (is (= "Chinese" (tracks/lang-label "zho")))
  (is (= "unknown" (tracks/lang-label "und")))
  (is (= "unknown" (tracks/lang-label nil)))
  (testing "a code the table does not know is shown as itself"
    (is (= "tlh" (tracks/lang-label "tlh")))))

(deftest audio-default-is-stored-then-browser-then-source-default-then-first
  (let [audio (:audio nabari)]
    (testing "the stored choice wins when the item has it"
      (is (= "jpn" (tracks/resolve-audio audio "jpn" "en-US")))
      (is (= "eng" (tracks/resolve-audio audio "en" "ja-JP")) "a two-letter stored code still finds the track"))
    (testing "a stored language the item lacks falls through to the browser's"
      (is (= "jpn" (tracks/resolve-audio audio "fre" "ja-JP"))))
    (testing "no stored choice: the browser's language"
      (is (= "jpn" (tracks/resolve-audio audio nil "ja")))
      (is (= "eng" (tracks/resolve-audio audio "" "en-GB"))))
    (testing "a browser language the item lacks: the source's default track"
      (is (= "eng" (tracks/resolve-audio audio nil "fr-FR")))
      (is (= "jpn" (tracks/resolve-audio [{:lang "eng" :default false} {:lang "jpn" :default true}] nil "de"))))
    (testing "no default marked: the first track"
      (is (= "eng" (tracks/resolve-audio [{:lang "eng"} {:lang "jpn"}] nil "de")))
      (is (= "und" (tracks/resolve-audio [{:lang "und"}] "jpn" "ja"))))
    (testing "no audio listed (an old row): nothing to resolve, dash.js picks"
      (is (nil? (tracks/resolve-audio [] "jpn" "ja")))
      (is (nil? (tracks/resolve-audio nil nil nil))))))

(deftest subtitles-default-to-the-stored-choice-else-off
  (let [subs (:subs nabari)]
    (is (= "eng" (tracks/resolve-subs subs "eng")))
    (is (= "eng" (tracks/resolve-subs subs "en")))
    (is (nil? (tracks/resolve-subs subs "jpn")) "a language the item lacks is off")
    (is (nil? (tracks/resolve-subs subs "off")) "off stored is off")
    (is (nil? (tracks/resolve-subs subs nil)) "nothing stored is off")
    (is (nil? (tracks/resolve-subs [] "eng")) "no subtitles is off")))

(deftest the-admin-summary-counts-audio-subs-and-bitmap
  (is (= {:audio 2 :subs 1 :bitmap 0} (tracks/tracks-summary nabari)))
  (is (= "2 audio · 1 subs" (tracks/tracks-text nabari)))
  (testing "bitmap subtitles, counted either way the probe marks them"
    (is (= "1 audio · 0 subs · 1 bitmap"
           (tracks/tracks-text {:audio [{:lang "eng"}] :subs [] :subs-unavailable 1})))
    (is (= "1 audio · 1 subs · 2 bitmap"
           (tracks/tracks-text {:audio [{:lang "eng"}]
                                :subs [{:lang "eng" :text? true} {:lang "eng" :text? false} {:lang "fre" :text? false}]}))))
  (testing "a row encoded before tracks were kept has no record"
    (is (nil? (tracks/tracks-summary nil)))
    (is (nil? (tracks/tracks-text nil)))))
