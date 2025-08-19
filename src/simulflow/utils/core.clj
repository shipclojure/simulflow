(ns simulflow.utils.core
  (:require
   [clojure.core.async :as a]
   [clojure.string :as str]
   [jsonista.core :as json])
  (:import
   (clojure.core.async.impl.channels ManyToManyChannel)
   (java.util Base64)))

(defmulti encode-base64 (fn [s] (class s)))

(defmethod encode-base64 String
  [s]
  (let [encoder (Base64/getEncoder)]
    (.encodeToString encoder (.getBytes s "UTF-8"))))

(defmethod encode-base64 (Class/forName "[B")
  [bytes]
  (let [encoder (Base64/getEncoder)]
    (.encodeToString encoder bytes)))

(defn decode-base64
  [s]
  (let [decoder (Base64/getDecoder)]
    (.decode decoder s)))

(defonce json-object-mapper (json/object-mapper {:decode-key-fn true}))

(defn parse-if-json
  "Parses a string as JSON if possible, otherwise returns the string."
  [s & {:keys [throw-on-error?]}]
  (try
    (json/read-value s json-object-mapper)
    (catch Exception e
      (if throw-on-error?
        (throw e)
        s))))

(defn json-str
  [m]
  (if (string? m)
    m
    (json/write-value-as-string m)))

(defn search-params
  [url]
  (let [params (second (str/split url #"\?"))
        param-list (if params (str/split params #"\&") [])]
    (reduce #(let [[k v] (str/split %2 #"\=")]
               (assoc %1 (keyword k) v)) {} param-list)))

(defn strip-search-params
  [url]
  (first (str/split url #"\?")))

(defn append-search-params
  [url search-params-m]
  (let [search-params (merge (search-params url)
                             search-params-m)
        search (->> (map (fn [[k v]] (str (name k) "=" (if (keyword? v) (name v) v))) search-params)
                    (str/join #"&")
                    (str "?"))]
    (str (strip-search-params url) search)))

(def end-of-sentence-pattern
  "Matches end of sentences with following rules:
  - (?<![A-Z]): Not after uppercase letters (e.g., U.S.A.)
  - (?<!\\d): Not preceded by digits (e.g 1. Let's start)
  - (?<!\\d\\s[ap]): Not after time markers (e.g., 3 a.m.)
  - (?<!Mr|Ms|Dr|Dl)(?<!Mrs|Dna|)(?<!Prof): Not after common titles (Mr., Ms.,
  Dr., Prof.)
  - [\\.\\?\\!:;]|[。？！：；]: Matches standard and full-width Asian
  punctuation"
  #"(?<![A-Z])(?<!\d)(?<!\d\s[ap])(?<!Mr|Ms|Dr)(?<!Mrs)(?<!Prof)[\.\?\!:;]|[。？！：；]$")

(defn ends-with-sentence? [text]
  (boolean (re-find end-of-sentence-pattern text)))

(defn end-sentence-pattern
  "Escape punctuation so it can be used in regex operations"
  [end-sentence]
  (re-pattern (str/replace end-sentence #"([\.|\?|\!|\:|\;])" "\\\\$1")))

(defn assemble-sentence
  "Assembles text chunks into complete sentences by detecting sentence boundaries.
   Takes an accumulator (previous incomplete text) and a new text chunk, returns
   a map containing any complete sentence and remaining text.

   Parameters:
   - accumulator: String containing previously accumulated incomplete text
   - llm-text-chunk-frame: New text chunk to be processed

   Returns a map with:
   - :sentence - Complete sentence including ending punctuation, or nil if no complete sentence
   - :accumulator - Remaining text that doesn't form a complete sentence yet

   Examples:
   (assemble-sentence \"Hello, \" \"world.\")
   ;; => {:sentence \"Hello, world.\" :accumulator \"\"}

   (assemble-sentence \"Hello\" \", world\")
   ;; => {:sentence nil :accumulator \"Hello, world\"}

   (assemble-sentence \"The U.S.A. is \" \"great!\")
   ;; => {:sentence \"The U.S.A. is great!\" :accumulator \"\"}

   Note: Uses end-of-sentence-pattern to detect sentence boundaries while handling
   special cases like abbreviations (U.S.A.), titles (Mr., Dr.), and various
   punctuation marks (.?!:;)."
  [accumulator llm-text-chunk-frame]
  (let [potential-sentence (str accumulator llm-text-chunk-frame)]
    (if-let [end-sentence-match (re-find end-of-sentence-pattern potential-sentence)]
      ;; Found sentence boundary - split and include the ending punctuation
      (let [[sentence new-acc]
            (str/split potential-sentence (end-sentence-pattern end-sentence-match) 2)]
        {:sentence (str sentence end-sentence-match)
         :accumulator new-acc})
      ;; No sentence boundary - accumulate text
      {:sentence nil
       :accumulator potential-sentence})))

(defn user-last-message?
  [context]
  (#{:user "user"} (-> context last :role)))

(defn assistant-last-message?
  [context]
  (#{:assistant "assistant"} (-> context last :role)))

(def token-content "Extract token content from streaming chat completions" (comp :content :delta first :choices))

(defn ->tool-fn
  "Strip extra parameters from a tool declaration"
  [tool]
  (update-in tool [:function] dissoc :transition-to :transition-cb :handler))

(defn without-nils
  "Given a map, return a map removing key-value
  pairs when value is `nil`."
  ([]
   (remove (comp nil? val)))
  ([data]
   (reduce-kv (fn [data k v]
                (if (nil? v)
                  (dissoc data k)
                  data))
              data
              data)))

(defn deep-merge [& maps]
  (letfn [(reconcile-keys [val-in-result val-in-latter]
            (if (and (map? val-in-result)
                     (map? val-in-latter))
              (merge-with reconcile-keys val-in-result val-in-latter)
              val-in-latter))
          (reconcile-maps [result latter]
            (merge-with reconcile-keys result latter))]
    (reduce reconcile-maps maps)))

(defn chan?
  "Returns true if c is a core.async channel"
  [c]
  (instance? ManyToManyChannel c))

(defn await-or-return
  "Call f with args. If the result is a channel, await the result"
  [f args]
  (let [r (f args)]
    (if (chan? r)
      (a/<!! r)
      r)))

(defn mono-time
  "Monotonic time in milliseconds. Used to check if we should send the next chunk
  of audio."
  []
  (long (/ (System/nanoTime) 1e6)))

;;; Aliases utils to prevent breaking changes
;;; Taken from https://github.com/taoensso/encore/blob/292cd788830a9f855607e8d847c61df3c18f0941/src/taoensso/encore.cljc#L571
(defn ^:no-doc alias-link-var
  "Private, don't use."
  [dst-var src-var dst-attrs]
  (add-watch src-var dst-var
             (fn [_ _ _ new-val]
               (alter-var-root dst-var (fn [_] new-val))
               ;; Wait for src-var meta to change. This is hacky, but
               ;; generally only relevant for REPL dev so seems tolerable.
               (let [t (Thread/currentThread)]
                 (future
                   (.join t 100)
                   (reset-meta! dst-var
                                (merge (meta src-var) dst-attrs)))))))

(defn ^:no-doc var-info
  "Private, don't use.
      Returns ?{:keys [var sym ns name meta ...]} for given symbol."
  [macro-env sym]
  (when (symbol? sym)
    (if (:ns macro-env)
      (let [ns (find-ns 'cljs.analyzer.api)
            v  (ns-resolve ns 'resolve)] ; Don't cache!
        (when-let [{:as m, var-ns :ns, var-name :name} ; ?{:keys [meta ns name ...]}
                   (@v macro-env sym)]
          (when var-ns ; Skip locals
            (assoc m :sym (symbol (str var-ns) (name var-name))))))

      (when-let [v (resolve macro-env sym)]
        (let [{:as m, var-ns :ns, var-name :name} (meta v)]
          {:var  v
           :sym  (symbol (str var-ns) (name var-name))
           :ns   var-ns
           :name var-name
           :meta
           (if-let [x (get m :arglists)]
             (assoc m :arglists `'~x) ; Quote
             (do    m))})))))

(defmacro defalias
  "Defines a local alias for the var identified by given qualified
     source symbol: (defalias my-map clojure.core/map), etc.

     Source var's metadata will be preserved (docstring, arglists, etc.).
     Changes to Clj source var's value will also be applied to alias.
     See also `defaliases`."
  ([src] `(defalias nil    ~src nil          nil))
  ([alias src] `(defalias ~alias ~src nil          nil))
  ([alias src alias-attrs] `(defalias ~alias ~src ~alias-attrs nil))
  ([alias src alias-attrs alias-body]
   (let [cljs?     (some? (:ns &env))
         src-sym   (if (symbol? src) src (throw (ex-info "Source must be a symbol" {:src src})))
         alias-sym (if (symbol? (or alias (symbol (name src-sym))))
                     (or alias (symbol (name src-sym)))
                     (throw (ex-info "Alias must be a symbol" {:alias alias})))

         src-var-info (var-info &env src-sym)
         {src-var :var src-attrs :meta} src-var-info

         alias-attrs
         (if (string? alias-attrs) ; Back compatibility
           {:doc      alias-attrs}
           (do        alias-attrs))

         link?       (get    alias-attrs :link? true)
         alias-attrs (dissoc alias-attrs :link?)

         final-attrs
         (select-keys (merge src-attrs (meta src-sym) (meta alias-sym) alias-attrs)
                      [:doc :no-doc :arglists :private :macro :added :deprecated :inline :tag :redef])

         alias-sym   (with-meta alias-sym final-attrs)
         alias-body  (or alias-body (if cljs? src-sym `@~src-var))]

     (when-not src-var-info
       (throw
         (ex-info (str "Source var not found: " src)
                  {:src src, :ns (str *ns*)})))

     (if cljs?
       `(def ~alias-sym ~alias-body)
       `(do
          ;; Need `alter-meta!` to reliably retain macro status!
          (alter-meta!                 (def ~alias-sym ~alias-body) merge ~final-attrs)
          (when ~link? (alias-link-var (var ~alias-sym) ~src-var         ~alias-attrs))
          ;; (assert (bound? (var ~alias-sym)) ~(str "Alias `" alias-sym "` is bound"))
          (do                (var ~alias-sym)))))))

(defmacro defaliases
  "Bulk version of `defalias`.
     Takes source symbols or {:keys [alias src attrs body]} maps:
       (defaliases
         {:alias my-map, :src map, :attrs {:doc \"My `map` alias\"}}
         {:alias my-vec, :src vec, :attrs {:doc \"My `vec` alias\"}})"
  {:arglists '([{:keys [alias src attrs body]} ...])}
  [& clauses]
  `(do
     ~@(map
         (fn [x]
           (cond
             (symbol? x) `(defalias ~x)
             (map?    x)
             (let [{:keys [alias src attrs body]
                    :or   {attrs (dissoc x :alias :src)}} x]
               `(defalias ~alias ~src ~attrs ~body))

             :else
             (throw (ex-info "Expected symbol or map"
                             {:clause x
                              :expected '#{symbol map}}))))
         clauses)))

(defn content-type
  "Extracts Content-Type header value from a header map, trying multiple variations.
  Tries \"Content-Type\", \"content-type\", and :content-type in that order.
  Returns the first match found, or nil if none found."
  [headers]
  (when headers
    (or (get headers "Content-Type")
        (get headers "content-type")
        (get headers :content-type))))
