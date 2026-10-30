(ns goob-toob.tracks
  "Audio and subtitle track choices, the pure part: which language a viewer
  gets by default, how a track is labelled, how the admin table sums a
  video's tracks. No platform deps so the decisions test on the JVM and the
  watch page compiles them to JS. Languages are ISO 639-2 codes as the rips
  and ffmpeg's dash muxer carry them (eng, jpn, und); a browser's
  navigator.language is BCP 47 (ja-JP), hence the mapping here."
  (:require [clojure.string :as str]))

(def ^:private languages
  ;; [name iso-639-1 & iso-639-2 codes (bibliographic first, then terminologic)]
  [["English"    "en" "eng"]
   ["Japanese"   "ja" "jpn"]
   ["Spanish"    "es" "spa"]
   ["French"     "fr" "fre" "fra"]
   ["German"     "de" "ger" "deu"]
   ["Italian"    "it" "ita"]
   ["Portuguese" "pt" "por"]
   ["Russian"    "ru" "rus"]
   ["Korean"     "ko" "kor"]
   ["Chinese"    "zh" "chi" "zho"]
   ["Dutch"      "nl" "dut" "nld"]
   ["Polish"     "pl" "pol"]
   ["Swedish"    "sv" "swe"]
   ["Arabic"     "ar" "ara"]
   ["Hindi"      "hi" "hin"]
   ["Turkish"    "tr" "tur"]])

(def ^:private name-of
  ;; every code of a language, 639-1 included, to its English name
  (into {"und" "unknown"}
        (for [[n & codes] languages, c codes] [c n])))

(def ^:private two-letter->iso
  ;; a browser's primary subtag to the code the manifests carry (the
  ;; bibliographic 639-2, what ffmpeg writes from a rip's tags)
  (into {} (for [[_ two three] languages] [two three])))

(defn lang-aliases
  "Every spelling of lang worth matching a manifest's lang= against: the
  code itself plus its 639-1 and the other 639-2 form, so a stored \"fre\"
  finds a track tagged \"fra\" or \"fr\". Nil or blank: the empty set."
  [lang]
  (if (str/blank? lang)
    #{}
    (let [l (str/lower-case (str/trim lang))]
      (or (first (for [[_ & codes] languages :when (some #{l} codes)] (set codes)))
          #{l}))))

(defn same-lang?
  "Two codes name the same language (eng = en = eng; und = und)."
  [a b]
  (boolean (and (not (str/blank? a)) (not (str/blank? b))
                (contains? (lang-aliases a) (str/lower-case (str/trim b))))))

(defn lang-regex-source
  "The source of a RegExp that matches exactly one of lang's aliases: what
  dash.js's initial-settings matcher gets, because handed a *string* it
  runs bcp-47-normalize over it first (eng -> en) and then fails to match
  the manifest's own \"eng\"."
  [lang]
  (str "^(" (str/join "|" (sort (lang-aliases lang))) ")$"))

(defn browser-lang->iso
  "navigator.language (ja, ja-JP, en-US, pt-BR...) to the 639-2 code of
  its primary subtag; a primary subtag that is already three letters
  comes back as is; nil when there is nothing to go on."
  [bcp47]
  (when-not (str/blank? bcp47)
    (let [primary (-> bcp47 str/trim str/lower-case (str/split #"[-_]") first)]
      (cond
        (= 2 (count primary)) (two-letter->iso primary)
        (= 3 (count primary)) primary
        :else nil))))

(defn lang-label
  "What a select option says for a track: its title when the rip gave it
  one (\"Commentary\"), else the language's name, else the code itself;
  no code at all reads \"unknown\"."
  ([lang] (lang-label lang nil))
  ([lang title]
   (cond
     (not (str/blank? title)) (str/trim title)
     (str/blank? lang) "unknown"
     :else (or (name-of (str/lower-case (str/trim lang))) lang))))

(defn resolve-audio
  "The audio language this viewer starts an item on: their stored choice
  when the item has that language, else the first track in the browser's
  language, else the one the source marks default, else the first. Nil
  when the item lists no audio (an old row: dash.js picks on its own).
  audio is the descriptor's [{:lang :title :channels :default} ...]."
  [audio stored browser-lang]
  (when (seq audio)
    (let [langs (map :lang audio)
          by-aliases (fn [code] (first (filter #(same-lang? code %) langs)))]
      (or (when-not (str/blank? stored) (by-aliases stored))
          (some-> (browser-lang->iso browser-lang) by-aliases)
          (:lang (first (filter :default audio)))
          (first langs)))))

(defn resolve-subs
  "The subtitle language shown at the start of an item: the stored choice
  when the item has it, else nil (off). \"off\" stored is a choice too and
  reads as nil here."
  [subs stored]
  (when-not (str/blank? stored)
    (first (filter #(same-lang? stored %) (map :lang subs)))))

(defn tracks-summary
  "Counts from a library row's :tracks ({:audio [...] :subs [...]} plus
  whichever way the probe marks bitmap subtitles: :subs-unavailable, or
  :subs entries with :text? false); nil for a row encoded before tracks
  were kept."
  [tracks]
  (when (map? tracks)
    (let [subs (:subs tracks)
          text (remove #(false? (:text? %)) subs)
          bitmap (filter #(false? (:text? %)) subs)]
      {:audio (count (:audio tracks))
       :subs (count text)
       :bitmap (+ (count bitmap) (or (:subs-unavailable tracks) 0))})))

(defn tracks-text
  "\"2 audio · 1 subs\" (\" · 1 bitmap\" when some could not be converted);
  nil when the row has no track record."
  [tracks]
  (when-let [{:keys [audio subs bitmap]} (tracks-summary tracks)]
    (str/join " · " (cond-> [(str audio " audio") (str subs " subs")]
                      (pos? bitmap) (conj (str bitmap " bitmap"))))))
