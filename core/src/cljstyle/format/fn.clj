(ns cljstyle.format.fn
  (:require
    [cljstyle.format.edit :as edit]
    [cljstyle.format.zloc :as zl]
    [rewrite-clj.zip :as z]))


(defn- vector-node?
  "True if the node at this location is a vector node."
  [zloc]
  (z/vector? (zl/unwrap-meta zloc)))


(defn- no-prev?
  "True if no prior location in this form matches the predicate."
  [zloc p?]
  (nil? (z/find-next zloc z/left p?)))


(defn- fn-sym?
  "True if the symbol is a function declaration."
  [sym]
  (and (symbol? sym)
       (contains? #{"fn" "defn" "defn-" "defmacro"}
                  (name sym))))


(defn- fn-form?
  "True if the node at this location is a function form."
  [zloc]
  (and (= :list (z/tag zloc))
       (let [form-sym (zl/form-symbol (z/down zloc))]
         (and (symbol? form-sym)
              (or (fn-sym? form-sym)
                  (and (vector-node? (z/up zloc))
                       (no-prev? zloc vector-node?)
                       (= 'letfn (zl/form-symbol (z/up zloc)))))))))


(defn- arg-vector?
  "True if the node at this location is an argument vector to a function."
  [zloc]
  (and (vector-node? zloc)
       (no-prev? zloc vector-node?)
       (or (fn-form? (z/up zloc))
           (and (= :list (z/tag (z/up zloc)))
                (fn-form? (z/up (z/up zloc)))))))


(defn- preceeding-symbols
  "Return a vector of all the symbols to the left of this location at the
  current level."
  [zloc]
  (into []
        (comp (take-while some?)
              (keep (comp zl/token-value zl/unwrap-meta))
              (filter symbol?))
        (iterate z/left (z/left zloc))))


(defn- fn-name?
  "True if this location is a function name symbol."
  [zloc]
  (let [unwrapped (zl/unwrap-meta zloc)]
    (and (fn-form? (z/up zloc))
         (zl/token? unwrapped)
         (no-prev? zloc arg-vector?)
         (symbol? (z/sexpr unwrapped))
         (not (fn-sym? (z/sexpr unwrapped)))
         (let [preceeding (preceeding-symbols zloc)]
           (or (empty? preceeding)
               (and (= 1 (count preceeding))
                    (fn-sym? (first preceeding))))))))


(defn- fn-to-name-or-args-space?
  "True if the node at this location is whitespace between a function's header
  and the name or argument vector."
  [zloc]
  (and (z/whitespace? zloc)
       (fn-form? (z/up zloc))
       (no-prev? zloc (some-fn fn-name? arg-vector?))))


(defn- pre-body-space?
  "True if this location is whitespace before a function arity body."
  [zloc]
  (and (z/whitespace? zloc)
       (fn-form? (z/up zloc))
       (= :list (some-> zloc z/right z/tag))))


(defn- post-name-space?
  "True if the node at this location is whitespace immediately following a
  function name."
  [zloc]
  (and (z/whitespace? zloc)
       (fn-name? (z/left zloc))))


(defn- post-doc-space?
  "True if the node at this location is whitespace immediately following a
  function docstring."
  [zloc]
  (and (z/whitespace? zloc)
       (fn-name? (z/left (z/left zloc)))
       (zl/string? (z/left zloc))))


(defn- post-args-space?
  "True if the node at this location is whitespace immediately following a
  function argument vector."
  [zloc]
  (and (z/whitespace? zloc)
       (arg-vector? (z/left zloc))))


(defn- defn-or-multiline?
  "True if this location is inside a `defn` or a multi-line form."
  [zloc]
  (or (when-let [fsym (zl/form-symbol zloc)]
        (and (symbol? fsym)
             (fn-sym? fsym)
             (not= "fn" (name fsym))))
      (zl/multiline? (z/up zloc))))



;; ## Editing Functions

(defn line-break-functions
  "Transform this form by applying line-breaks to function definition forms."
  [form]
  (-> form
      ;; Function name or args should be adjacent to definition.
      (edit/break-whitespace
        fn-to-name-or-args-space?
        (constantly false))
      ;; If the function is a defn or multline, break after the name.
      (edit/break-whitespace
        post-name-space?
        defn-or-multiline?)
      ;; Always line-break after the docstring.
      (edit/break-whitespace
        post-doc-space?
        (constantly true))
      ;; Line-break after the arguments unless this is a one-liner.
      (edit/break-whitespace
        post-args-space?
        defn-or-multiline?)
      ;; Line-break before the body unless this is a one-liner.
      (edit/break-whitespace
        pre-body-space?
        defn-or-multiline?)))
