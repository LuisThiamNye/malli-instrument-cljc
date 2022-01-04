(ns malli-instrument.core
  #?(:clj (:refer-clojure :exclude [alter-var-root])
     :cljs (:require-macros malli-instrument.core))
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [alter-cljs.core :refer [alter-var-root if-cljs]]))

;; Note: cannot use alter-meta! to store original function as cljs meta of vars cannot be changed at runtime (statically determined at compile-time)
(defonce original-fns (atom {}))

(defn safe-humanize
  "Malli can throw when calling explain or humanize on some inputs. This is undesirable
   as it generates very obscure error messages. This function wraps the calls to humanize
   with try to avoid crashing in instrumented functions"
  [schema, data]
  (let [explained (try {:ok (m/explain schema data)}
                       (catch #?(:cljs :default :clj Exception) e
                         {:error e}))
        humanized (if-let [explained (:ok explained)]
                    (try {:ok (me/humanize explained)}
                         (catch #?(:cljs :default :clj Exception) e
                           {:error e}))
                    {:ok explained})]
    (if-let [humanized (:ok humanized)]
      humanized
      {:message     (str "There was an error when generating human-readable string"
                         " (Could be this: https://github.com/metosin/malli/issues/321)")
       :explain-raw explained
       :exception   (:error humanized)})))

(defn- wrap-with-validate-input
  "Wraps the given function `f` with code that will validate its input arguments
   with the provided malli `schema`."
  [v f schema]
  {:pre [(some? schema)]}
  (fn [& args]
    (if (m/validate schema args)
      (apply f args)
      (throw (ex-info "Function received wrong input"
                      {:error (safe-humanize schema args)
                       :value args
                       :fn    v})))))

(defn- wrap-with-validate-output
  "Similar to `wrap-with-validate-input`, but checks return value instead."
  [v f schema]
  {:pre [(some? schema)]}
  (fn [& args]
    (let [result (apply f args)]
      (if (m/validate schema result)
        result
        (throw (ex-info "Function returned wrong output"
                        {:error (safe-humanize schema result)
                         :value result
                         :fn    v}))))))

(defn wrap-with-instrumentation
  "Combines `wrap-with-validate-input` and `wrap-with-validate-output` for
  complete instrumentation"
  [v f args-schema ret-schema]
  (wrap-with-validate-input v (wrap-with-validate-output v f ret-schema) args-schema))

(defn get-fn-schema
  "Given a function's symbol namespace and name, finds a registered malli schema in
   the global function registry. See `malli.core/function-schemas`."
  [the-ns the-name]
  {:pre [(symbol? the-ns) (symbol? the-name)]}
  (let [fn-schema (m/form (get-in (m/function-schemas) [the-ns the-name :schema]))]
    {:args (-> fn-schema (nth 1))
     :ret  (-> fn-schema (nth 2))}))

#?(:clj
   (defmacro locate-var
     "Given a namespace and name symbols, returns the var in that namespace if found,
   nil otherwise."
     [the-ns, the-name]
     (try (let [sym (symbol (str the-ns "/" the-name))]
            `(if-cljs
              (var ~sym)
              (find-var ~sym)))
          (catch java.lang.IllegalArgumentException _ nil))))

#?(:clj
   (defmacro instrument-one!
     "Given a function's symbol namespace and name, instruments the function by
  altering its current definition. The original function is preserved in the
  metadata to allow restoring via `unstrument-one!`. This operation is
  idempotent, multiple runs will not wrap the function more than once."
     [the-ns the-name]
     `(let [{args# :args ret# :ret} (get-fn-schema '~the-ns, '~the-name)
            the-var#                (locate-var ~the-ns ~the-name)]
        (if the-var#
          (let [original-fn# (or (get-in original-fns ['~the-ns '~the-name]) (deref the-var#))]
            (swap! original-fns assoc-in ['~the-ns '~the-name] original-fn#)
            (alter-var-root
             the-var#
             (constantly (wrap-with-instrumentation the-var# original-fn# args# ret#))))

          (throw (ex-info (if-cljs
                           (str "Attempting to instrument non-existing var " '~the-ns "/" '~the-name)
                           (format "Attempting to instrument non-existing var %s/%s" '~the-ns '~the-name))
                          {:error :VAR_NOT_FOUND
                           :ns    '~the-ns, :fn-name '~the-name}))))))

#?(:clj
   (defmacro unstrument-one!
     "Undoes the instrumentation performed by `instrument-one!`, leaving the var as
  it was originally defined."
     [the-ns, the-name]
     `(let [the-var# (locate-var ~the-ns ~the-name)]
        (if the-var#
          (let [original-fn# (or (get-in original-fns ['~the-ns '~the-name]) (deref the-var#))]
            (swap! original-fns update '~the-ns dissoc '~the-name)
            (alter-var-root
             the-var#
             (constantly original-fn#)))
          (throw (ex-info (if-cljs
                           (str "Attempting to unstrument non-existing var " '~the-ns "/" '~the-name)
                           (format "Attempting to unstrument non-existing var %s/%s" '~the-ns '~the-name))
                          {:error :VAR_NOT_FOUND
                           :ns    '~the-ns, :fn-name '~the-name}))))))

#?(:clj
   (defmacro instrument-all!
     "Goes over all schemas in malli's function registry and performs instrumentation
   by running `instrument-one!` for each of them."
     []
     (let [errors-sym (gensym)]
       `(let [~errors-sym (atom [])]
          (do
            ~@(into []
                    (mapcat (fn [[namesp funs]]
                              (mapv (fn [[fun-name _]]
                                      `(try (instrument-one! ~namesp ~fun-name)
                                            (catch ~(if (:ns &env) 'js/Error 'Exception) e#
                                              (swap! ~errors-sym conj e#))))
                                    funs)))
                    (m/function-schemas)))
          (when (seq @~errors-sym)
            (throw (ex-info "There were unexpected errors during instrumentation"
                            {:errors @~errors-sym})))))))

#?(:clj
   (defmacro unstrument-all!
     "Goes over all schemas in malli's function registry and performs unstrumentation
   by running `unstrument-one!` for each of them."
     []
     (let [errors-sym (gensym)]
       `(let [~errors-sym (atom [])]
          (do
            ~@(into []
                    (mapcat (fn [[namesp funs]]
                              (mapv (fn [[fun-name _]]
                                      `(try (unstrument-one! ~namesp ~fun-name)
                                            (catch ~(if (:ns &env) 'js/Error 'Exception) e#
                                              (swap! ~errors-sym conj e#))))
                                    funs)))
                    (m/function-schemas)))
          (when (seq @~errors-sym)
            (throw (ex-info "There were unexpected errors during instrumentation"
                            {:errors @~errors-sym})))))))
