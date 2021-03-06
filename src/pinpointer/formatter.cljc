(ns pinpointer.formatter
  #?(:clj (:refer-clojure :exclude [format]))
  (:require [clojure.spec.alpha :as s]
            [fipp.edn :as edn]
            [fipp.engine :as fipp]
            [fipp.visit :as visit]))

(def ^:dynamic *highlighting-mark* "!!!")

(defmulti render
  (fn [{:keys [spec]} printer x] (when (seq? spec) (first spec))))
(defmethod render :default [_ printer x]
  (let [spec (-> (:trace printer) first :spec)
        msg (str "spec " spec
                 " must have its own method implementation of " `render)]
    (throw (ex-info msg {:spec spec}))))

(defn- highlight [x]
  [:span [:escaped *highlighting-mark*] x [:escaped *highlighting-mark*]])

(defn- wrap [f {:keys [trace] :as printer} x]
  (let [frame (first trace)]
    (if (= x (:val frame))
      (if (and (= (count trace) 1) (not (:reason frame)))
        (highlight (f (:base-printer printer) x))
        (render frame printer x))
      (f (:base-printer printer) x))))

(defn pop-trace [printer]
  (update printer :trace rest))

(defrecord HighlightPrinter [base-printer trace]
  visit/IVisitor
  (visit-unknown [this x]
    (wrap visit/visit-unknown this x))
  (visit-nil [this]
    (wrap (fn [printer _] (visit/visit-nil printer)) this nil))
  (visit-boolean [this x]
    (wrap visit/visit-boolean this x))
  (visit-string [this x]
    (wrap visit/visit-string this x))
  (visit-character [this x]
    (wrap visit/visit-character this x))
  (visit-symbol [this x]
    (wrap visit/visit-symbol this x))
  (visit-keyword [this x]
    (wrap visit/visit-keyword this x))
  (visit-number [this x]
    (wrap visit/visit-number this x))
  (visit-seq [this x]
    (wrap visit/visit-seq this x))
  (visit-vector [this x]
    (wrap visit/visit-vector this x))
  (visit-map [this x]
    (wrap visit/visit-map this x))
  (visit-set [this x]
    (wrap visit/visit-set this x))
  (visit-tagged [this x]
    (wrap visit/visit-tagged this x))
  (visit-meta [this meta x]
    (wrap #(visit/visit-meta %1 meta %2) this x))
  (visit-var [this x]
    (wrap visit/visit-var this x))
  (visit-pattern [this x]
    (wrap visit/visit-pattern this x))
  (visit-record [this x]
    (wrap visit/visit-record this x))
  )

(defn format
  ([x trace] (format x trace {}))
  ([x trace opts]
   (let [base-printer (or (:base-printer opts)
                          (edn/map->EdnPrinter {:symbols {}}))
         printer (->HighlightPrinter base-printer trace)]
     (with-out-str
       (fipp/pprint-document (visit/visit printer x)
                             (dissoc opts :base-printer))))))

;;
;; Method implementations of `render`
;;

(defn- render-next [printer x]
  (visit/visit (pop-trace printer) x))

(defmethod render `s/spec [frame printer x]
  (render-next printer x))

(defmethod render `s/and [frame printer x]
  (render-next printer x))

(defmethod render `s/or [frame printer x]
  (render-next printer x))

(defmethod render `s/nilable [frame printer x]
  (render-next printer x))

(defn- pretty-coll [printer open xs sep close f]
  (let [xform (comp (map-indexed #(f printer %1 %2))
                    (interpose sep))
        ys (sequence xform xs)]
    [:group open ys close]))

(defn render-coll
  ([frame printer x]
   (render-coll frame printer x nil))
  ([{[n] :steps} printer x each-fn]
   (let [[open close] (cond (seq? x) ["(" ")"]
                            (vector? x) ["[" "]"]
                            (map? x) ["{" "}"]
                            (set? x) ["#{" "}"])
         sep (if (map? x) [:span "," :line] :line)
         each-fn (if each-fn
                   #(each-fn %2 %3)
                   (fn [_ i x]
                     (visit/visit (cond-> printer (= i n) pop-trace) x)))]
     (pretty-coll printer open x sep close each-fn))))

(defmethod render `s/tuple [{:keys [steps] :as frame} printer x]
  (if (empty? steps)
    (render-next printer x)
    (render-coll frame printer x)))

(defn- render-every [{:keys [steps] :as frame} printer x]
  (if (empty? steps)
    (render-next printer x)
    (render-coll frame printer x)))

(defmethod render `s/every [frame printer x]
  (render-every frame printer x))

(defmethod render `s/coll-of [frame printer x]
  (render-every frame printer x))

(defn- render-every-kv [{:keys [steps] :as frame} printer x]
  (if (empty? steps)
    (render-next printer x)
    (let [[key k-or-v] steps]
      (render-coll frame printer x
        (fn [i [k v]]
          (let [kprinter (cond-> printer
                           (and (= k key) (= k-or-v 0))
                           pop-trace)
                vprinter (cond-> printer
                           (and (= k key) (= k-or-v 1))
                           pop-trace)]
            [:span (visit/visit kprinter k) " " (visit/visit vprinter v)]))))))

(defmethod render `s/every-kv [frame printer x]
  (render-every-kv frame printer x))

(defmethod render `s/map-of [frame printer x]
  (render-every-kv frame printer x))

(defmethod render `s/keys [{[key] :steps :as frame} printer x]
  (if (nil? key)
    (visit/visit (pop-trace printer) x)
    (render-coll frame printer x
      (fn [i [k v]]
        (let [vprinter (cond-> printer (= k key) pop-trace)]
          [:span (visit/visit printer k) " " (visit/visit vprinter v)])))))

(defmethod render `s/merge [frame printer x]
  (render-next printer x))

(defn- render-regex [{:keys [reason steps] :as frame} printer x]
  (cond (= reason "Extra input")
        (let [printer (:base-printer printer)]
          (render-coll frame printer x
            (fn [i v]
              (cond-> (visit/visit printer v)
                (>= i (first steps)) highlight))))

        (or (= reason "Insufficient input") ;; special case for s/alt
            (when-let [next-frame (second (:trace printer))]
              (= (:reason next-frame) "Insufficient input")))
        (let [printer (:base-printer printer)
              x (if (seq? x) (concat x ['...]) (conj x '...))]
          (render-coll frame printer x
            (fn [i v]
              (cond-> (visit/visit printer v)
                (= i (dec (count x))) highlight))))

        (empty? steps)
        (render-next printer x)

        :else (render-coll frame printer x)))

(defmethod render `s/cat [frame printer x]
  (render-regex frame printer x))

(defmethod render `s/& [frame printer x]
  (render-regex frame printer x))

(defmethod render `s/alt [frame printer x]
  (render-regex frame printer x))

(defmethod render `s/? [frame printer x]
  (render-regex frame printer x))

(defmethod render `s/* [frame printer x]
  (render-regex frame printer x))

(defmethod render `s/+ [frame printer x]
  (render-regex frame printer x))

(defmethod render `s/multi-spec [frame printer x]
  (if (= (:reason frame) "no method")
    (highlight (visit/visit (:base-printer printer) x))
    (render-next printer x)))

(defmethod render `s/nonconforming [frame printer x]
  (render-next printer x))
