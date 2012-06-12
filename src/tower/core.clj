(ns tower.core
  "Simple internationalization (i18n) library for Clojure. Wraps standard Java
  functionality when possible, removing unnecessary boilerplate."
  {:author "Peter Taoussanis"}
  (:require [clojure.string :as str]
            [timbre.core    :as timbre])
  (:use     [tower.utils    :as utils :only (defmem-)])
  (:import  [java.util Date Locale]
            [java.text Collator NumberFormat DateFormat]))

;;;; Locales (big L for the Java object)

(defn- make-Locale
  "Creates a Java Locale object with a lowercase ISO-639 language code,
  optional uppercase ISO-3166 country code, and optional vender-specific variant
  code. Returns JVM's default Locale when called without args."
  ([]                     (Locale/getDefault))
  ([lang]                 (Locale. lang))
  ([lang country]         (Locale. lang country))
  ([lang country variant] (Locale. lang country variant)))

(def ^:private available-Locales (set (Locale/getAvailableLocales)))

(defn parse-Locale
  "Tries to create Locale from name string or keyword:
    :en, :en_US, :en_US_variant, etc.

  Returns JVM's default Locale for nil/blank name or when called without args.
  Throws an exception when no appropriate Locale can be created."
  ([] (parse-Locale ""))
  ([locale]
     {:post [(available-Locales %)]}
     (if (or (nil? locale) (str/blank? (name locale)))
       (make-Locale)
       (apply make-Locale (str/split (name locale) #"_")))))

(comment (parse-Locale)
         (parse-Locale :en_US)
         (parse-Locale :invalid))

;;;; Bindings

(def ^:dynamic *Locale* (make-Locale))
(def ^:dynamic *translation-scope* nil)

(defmacro with-locale
  [locale & body]
  `(binding [*Locale* (parse-Locale ~locale)] ~@body))

(defmacro with-scope
  [translation-scope & body]
  `(binding [*translation-scope* ~translation-scope] ~@body))

(defmacro with-i18n
  "Executes body within the context of thread-local i18n bindings, enabling use
  of translation and localization functions.

  'locale': :en, :en_US, :en_US_var1, etc.
  'translation-scope': nil, :example/greetings/main, etc."
  [locale translation-scope & body]
  `(with-local ~locale
     (with-scope ~translation-scope ~@body)))

;;;; Collation, etc.

(defmem- get-collator Collator [locale] (Collator/getInstance locale))

(defn l-compare "Localized Unicode comparator."
  [x y] (.compare (get-collator *Locale*) x y))

(comment (with-locale nil (sort l-compare ["a" "d" "c" "b" "f" "_"])))

(defn normalize
  "Transforms Unicode string into W3C-recommended standard de/composition form
  allowing easier searching and sorting of strings. Normalization is considered
  good hygiene when communicating with a DB or other software."
  [s] (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFC))

;;;; Localized number formatting

(defmem- f-number   NumberFormat [loc] (NumberFormat/getNumberInstance   loc))
(defmem- f-integer  NumberFormat [loc] (NumberFormat/getIntegerInstance  loc))
(defmem- f-percent  NumberFormat [loc] (NumberFormat/getPercentInstance  loc))
(defmem- f-currency NumberFormat [loc] (NumberFormat/getCurrencyInstance loc))

(defn format-number   [x] (.format (f-number   *Locale*) x))
(defn format-integer  [x] (.format (f-integer  *Locale*) x))
(defn format-percent  [x] (.format (f-percent  *Locale*) x))
(defn format-currency [x] (.format (f-currency *Locale*) x))

(defn parse-number    [s] (.parse (f-number   *Locale*) s))
(defn parse-integer   [s] (.parse (f-integer  *Locale*) s))
(defn parse-percent   [s] (.parse (f-percent  *Locale*) s))
(defn parse-currency  [s] (.parse (f-currency *Locale*) s))

(comment (with-locale :en_ZA (format-currency 200))
         (with-locale :en_ZA (parse-currency "R 200.33")))

;;;; Localized date/time formatting

(defmem- f-date DateFormat [st loc]    (DateFormat/getDateInstance st loc))
(defmem- f-time DateFormat [st loc]    (DateFormat/getTimeInstance st loc))
(defmem- f-dt   DateFormat [ds ts loc] (DateFormat/getDateTimeInstance ds ts loc))

(defn style
  "Returns a DateFormat time/date style constant by style key e/o
  #{:default :short :medium :long :full}."
  ([] (style :default))
  ([style]
     (case style
       :default DateFormat/DEFAULT
       :short   DateFormat/SHORT
       :medium  DateFormat/MEDIUM
       :long    DateFormat/LONG
       :full    DateFormat/FULL
       (throw (Exception. (str "Unknown style: " style))))))

(defn format-date
  ([d]       (format-date (style) d))
  ([style d] (.format (f-date style *Locale*) d)))

(defn format-time
  ([t]       (format-time (style) t))
  ([style t] (.format (f-time style *Locale*) t)))

(defn format-dt
  ([dt] (format-dt (style) (style) dt))
  ([date-style time-style dt]
     (.format (f-dt date-style time-style *Locale*) dt)))

(defn parse-date
  ([s]       (parse-date (style) s))
  ([style s] (.parse (f-date style *Locale*) s)))

(defn parse-time
  ([s]       (parse-time (style) s))
  ([style s] (.parse (f-time style *Locale*) s)))

(defn parse-dt
  ([s] (parse-dt (style) (style) s))
  ([date-style time-style s]
     (.parse (f-dt date-style time-style *Locale*) s)))

(comment (with-locale :en_ZA (format-date (Date.)))
         (with-locale :en_ZA (format-date (style :full) (Date.)))
         (with-locale :en_ZA (format-time (style :short) (Date.)))
         (with-locale :en_ZA (format-dt (style :full) (style :full) (Date.))))

;;;; Localized text formatting

(defn format-str
  "Like clojure.core/format but uses a locale."
  ^String [fmt & args]
  (String/format *Locale* fmt (to-array args)))

(defn format-msg
  "Creates a localized message formatter and parse pattern string, substituting
  given arguments as per MessageFormat spec."
  ^String [pattern & args]
  (let [formatter     (java.text.MessageFormat. pattern *Locale*)
        string-buffer (.format formatter (to-array args) (StringBuffer.) nil)]
    (.toString string-buffer)))

(comment
  (with-locale :de (format-msg "foobar {0}!" 102.22))
  (with-locale :de (format-msg "foobar {0,number,integer}!" 102.22))
  (-> #(format-msg "{0,choice,0#no cats|1#one cat|1<{0,number} cats}" %)
      (map (range 5))))

;;;; Dictionary management

(def ^:private compiled-dictionary
  "Compiled form of (:dictionary @translation-config) that:
    1. Is efficient to store.
    2. Is efficient to access.
    3. Has display-ready, decorator-controlled text values."
  (atom {}))

(def translation-config
  "To enable translations, dictionary should be a map of form
  {:locale {:ns1 ... {:nsN {:key<.optional-decorator> text}}}}}

  See source code for further details."
  (atom {:dictionary-compiler-options {:escape-undecorated? true}

         ;; Canonical example dictionary used for dev/debug, unit tests,
         ;; README, etc.
         :dictionary
         {:en         {:example {:foo ":en :example/foo text"
                                 :bar ":en :example/bar text"
                                 :decorated {:foo.html "<tag>"
                                             :foo.note "Translator note"
                                             :bar.md   "**strong**"
                                             :baz      "<tag>"}}}
          :en_US      {:example {:foo ":en_US :example/foo text"}}
          :en_US_var1 {:example {:foo ":en_US_var1 :example/foo text"}}}

         ;; Knobs for default missing-key-fn to allow convenient common tweaks
         ;; without needing to provide an entirely new fn
         :default-missing-key-fn-opts
         {:dev-mode?      true
          :default-locale :en
          :error-log-fn
          (fn [key locale] (timbre/error "Missing translation" key "for" locale))}

         :missing-key-fn
         (fn [{:keys [key locale]}]
           (let [;; Just grab extra config from atom: missing keys are uncommon
                 ;; and a more efficient missing-key-fn can be substituted if
                 ;; necessary
                 {:keys [dev-mode? default-locale error-log-fn]}
                 (:default-missing-key-fn-opts @translation-config)]
             (if dev-mode?
               (str "**" key "**")
               (do (error-log-fn key locale)
                   (get-in @compiled-dictionary [default-locale key]
                           "")))))}))

(defn set-translation-config!
  [[k & ks] val] (swap! translation-config assoc-in (cons k ks) val))

(defn- compile-map-path
  "[:locale :ns1 ... :nsN unscoped-key.decorator translation] =>
  {:locale {:ns1/.../nsN/unscoped-key (f translation decorator)}}"
  [{:keys [escape-undecorated?] :as compiler-options} path]
  {:pre [(seq path) (>= (count path) 3)]}
  (let [path        (vec path)
        locale-name (first path)
        translation (peek path)
        scope-ks    (subvec path 1 (- (count path) 2)) ; [:ns1 ... :nsN]

        ;; Check for possible decorator
        [unscoped-k decorator]
        (->> (str/split (name (peek (pop path))) #"[\._]")
             (map keyword))

        ;; [:ns1 ... :nsN :unscoped-key] => :ns1/.../nsN/unscoped-key
        scoped-key (->> (conj scope-ks unscoped-k)
                        (map name)
                        (str/join "/")
                        (keyword))]

    (when (not= decorator :note) ; Discard translator notes
      {locale-name
       {scoped-key
        (case decorator
          :html translation ; HTML-safe, leave unchanged

          ;; Escape and parse as inline markdown
          :md (-> (str translation)
                  (utils/escape-html)
                  (utils/inline-markdown->html))

          ;; No decorator
          (if escape-undecorated?
            (utils/escape-html translation)
            translation))}})))

(defn- compile-dictionary!
  "Compiles text translations stored in simple development-friendly Clojure map
  into form required by localized text translator.

    {:en {:example {:foo.html \"<tag>\"
                    :foo.note \"Translator note\"
                    :bar.md   \"**strong**\"
                    :baz      \"<tag>\"}}}
    =>
    {:en {:example/foo \"<tag>\"
          :example/bar \"<strong>strong</strong>\"
          :example/baz \"&lt;tag&gt;\"}}

  Note the optional key decorators."
  []
  (let [{:keys [dictionary dictionary-compiler-options]} @translation-config]
    (->> (utils/leaf-paths dictionary)
         (map (partial compile-map-path dictionary-compiler-options))
         (apply merge-with merge) ; 1-level recursive merge
         (reset! compiled-dictionary))))

(compile-dictionary!) ; Actually compile default dictionary now

;; Automatically re-compile any time dictionary changes
(add-watch
 translation-config "dictionary-watch"
 (fn [key ref old-state new-state]
   (when (not= (:dictionary old-state)
               (:dictionary new-state))
     (compile-dictionary!))))

(defn dictionary->xliff [m]) ; TODO Use hiccup?
(defn xliff->dictionary [s]) ; TODO Use clojure.xml/parse?

;;;; Translation

(defn- fqname
  "Like 'name' but nil-safe and returns fully-qualified name."
  [keyword]
  (when keyword
    (if-let [ns (namespace keyword)]
      (str ns "/" (name keyword))
      (name keyword))))

(comment (fqname :a/b/c/d))

(def ^:private locales-to-check
  "Given a Locale, returns vector of dictionary locale names to check, in order
  of preference:
    #<Locale en_US_var1> => [:en_US_var1 :en_US :en]
    #<Locale en_US>      => [:en_US :en]
    #<Locale en>         => [:en]"
  (memoize
   (fn [Locale]
     (let [parts (str/split (str Locale) #"_")]
       (vec (for [n (range (count parts) 0 -1)]
              (keyword (str/join "_" (take n parts)))))))))

(comment (locales-to-check (locale)))

(defn t ; translate
  "Localized text translator. Takes a namespaced key :nsA/.../nsN within a scope
  :ns1/.../nsM and returns the best dictionary translation available for working
  locale.

  With additional arguments, treats translated text as pattern for message
  formatting."
  ([scoped-dict-key & args] (apply format-msg (t scoped-dict-key) args))
  ([scoped-dict-key]
     (let [fully-scoped-key ; :ns1/.../nsM/nsA/.../nsN = :ns1/.../nsN
           (if *translation-scope*
             (keyword (str (fqname *translation-scope*) "/"
                           (fqname scoped-dict-key)))
             scoped-dict-key)

           [lchoice1 lchoice2 lchoice3] (locales-to-check *Locale*)
           cdict-snap @compiled-dictionary]

       (or (get-in cdict-snap [lchoice1 fully-scoped-key])
           (when lchoice2 (get-in cdict-snap [lchoice2 fully-scoped-key]))
           (when lchoice3 (get-in cdict-snap [lchoice3 fully-scoped-key]))
           ((:missing-key-fn @translation-config) {:key     fully-scoped-key
                                                   :locale *Locale*})))))

;;;; Dev/tests

(comment (with-i18n :en_ZA nil (t :example/foo))
         (with-i18n :en_ZA nil (with-scope :example (t :foo))))