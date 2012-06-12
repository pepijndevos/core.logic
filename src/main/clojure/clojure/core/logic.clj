(ns clojure.core.logic
  (:refer-clojure :exclude [==])
  (:use [clojure.walk :only [postwalk]])
  (:require [clojure.set :as set])
  (:import [java.io Writer]))

(def ^{:dynamic true} *occurs-check* true)
(def ^{:dynamic true} *reify-vars* true)
(def ^{:dynamic true} *locals*)
(def ^{:dynamic true} *expand-doms*)

;; =============================================================================
;; Core Protocols

;; -----------------------------------------------------------------------------
;; miniKanren Protocols

(defprotocol IUnifyTerms
  (unify-terms [u v s]))

(defprotocol IUnifyWithNil
  (unify-with-nil [v u s]))

(defprotocol IUnifyWithObject
  (unify-with-object [v u s]))

(defprotocol IUnifyWithLVar
  (unify-with-lvar [v u s]))

(defprotocol IUnifyWithLSeq
  (unify-with-lseq [v u s]))

(defprotocol IUnifyWithSequential
  (unify-with-seq [v u s]))

(defprotocol IUnifyWithMap
  (unify-with-map [v u s]))

(defprotocol IUnifyWithSet
  (unify-with-set [v u s]))

(defprotocol IReifyTerm
  (reify-term [v s]))

(defprotocol IWalkTerm
  (walk-term [v s]))

(defprotocol IOccursCheckTerm
  (occurs-check-term [v x s]))

(defprotocol IBuildTerm
  (build-term [u s]))

(defprotocol IBind
  (bind [this g]))

(defprotocol IMPlus
  (mplus [a f]))

(defprotocol ITake
  (take* [a]))

;; -----------------------------------------------------------------------------
;; cKanren protocols

(defprotocol IDomain
  (-idomain-marker [_]))

(defprotocol ISingletonDomain
  (-isingleton-domian-marker [_]))

(defn domain? [x]
  (satisfies? IDomain x))

(defprotocol IUnifyWithDomain
  (unify-with-dom [v u s]))

(defprotocol IConstraintStore
  (addc [this c])
  (updatec [this c])
  (containsc? [this c]))

(defprotocol IConstraint
  (proc [this])
  (rator [this])
  (rands [this])
  (process-prefix [this p]))

(defn constraint? [x]
  (satisfies? IConstraint x))

(defprotocol IReifiableConstraint
  (reifyc [this v r]))

(defprotocol IEnforceableConstraint
  (-ienforceable-constraint-marker [_]))

(defprotocol IFiniteDomain
  (lb [this])
  (ub [this])
  (bounds [this])
  (member? [this v])
  (disjoint? [this that])
  (intersects? [this that])
  (sub? [this that])
  (super? [this that])
  (drop-before [this n])
  (keep-before [this n])
  (expand [this])
  (intersection [this that])
  (difference [this that]))

(defprotocol IDisequalityConstrain
  (!=c [u v]))

(defprotocol IAllDiffConstrain
  (all-diffc [u v]))

(defprotocol IFDConstrain
  (=c [u v])
  (<c [u v])
  (<=c [u v])
  (+c [u v w]))

(defprotocol IForceAnswerTerm
  (-force-ans [u]))

;; =============================================================================
;; Pair

(defprotocol IPair
  (lhs [this])
  (rhs [this]))

(deftype Pair [lhs rhs]
  clojure.lang.Counted
  (count [_] 2)
  clojure.lang.Indexed
  (nth [_ i] (case i
                   0 lhs
                   1 rhs
                   (throw (IndexOutOfBoundsException.))))
  (nth [_ i not-found] (case i
                             0 lhs
                             1 rhs
                             not-found))
  IPair
  (lhs [_] lhs)
  (rhs [_] rhs)
  java.util.Map$Entry
  (getKey [_] lhs)
  (getValue [_] rhs)
  Object
  (toString [_]
    (str "(" lhs " . " rhs ")")))

(defn- ^Pair pair [lhs rhs]
  (Pair. lhs rhs))

;; =============================================================================
;; Constraint Store

(declare lvar?)

(defn var-rands [c]
  (into [] (filter lvar? (rands c))))

(defn vardiff [oc c]
  (let [ocr (rands oc)
        cr (rands c)]
    (reduce (fn [v i]
              (let [or (nth ocr i)
                    r (nth cr i)]
                (if (and (lvar? or) (not (lvar? r)))
                  (conj v or)
                  v)))
            [] (range (count (rands oc))))))

(deftype ConstraintStore [km cm cid]
  IConstraintStore
  (addc [this c]
    (let [vars (var-rands c)
          cid (inc cid)
          c (vary-meta c assoc :id cid)
          ^ConstraintStore cs (reduce (fn [m v] (assoc m v c)) this vars)]
      (ConstraintStore. (.km cs) (.cm cs) cid)))
  (updatec [this c]
    (let [id (-> c meta :id)
          oc (get cm cid)
          vs (vardiff oc c)
          nkm (reduce (fn [m v]
                        (let [s (disj (get km v) id)]
                          (if (empty? s)
                            (dissoc km v)
                            (assoc km v s))))
                      km vs)
          ncm (if (zero? (count (var-rands c)))
                (dissoc cm id)
                cm)]
      (ConstraintStore. km cm cid)))
  (containsc? [this c]
    (if (contains? cm (-> c meta :id))
      true
      false))
  clojure.lang.Counted
  (count [this]
    (count cm))
  clojure.lang.Associative
  (assoc [this k v]
    (when-not (lvar? k)
      (throw (Error. (str "constraint store assoc expected logic var key: " k))))
    (when-not (constraint? v)
      (throw (Error. (str "constraint store assoc expected constraint value: " v))))
    (when-not (-> v meta :id)
      (throw (Error. (str "constraint has no id " v))))
    (let [nkm (assoc-in km [k] (fnil (fn [s] (assoc s cid v)) #{}))
          ncm (assoc cm cid (with-meta v {:id cid}))]
      (ConstraintStore. nkm ncm cid)))
  clojure.lang.ILookup
  (valAt [this k]
    (when-not (lvar? k)
      (throw (Error. (str "constraint store lookup expected logic var key: " k))))
    (let [ids (get km k)]
      (map cm ids)))
  (valAt [this k not-found]
    (if-let [v (.valAt this k)]
      v
      not-found)))

(defn ^ConstraintStore make-cs []
  (ConstraintStore. {} {} 0))

;; =============================================================================
;; Substitutions

(defprotocol ISubstitutions
  (ext-no-check [this u v])
  (walk [this v]))

(defprotocol ISubstitutionsCLP
  (update [this x v]))

(declare empty-s)
(declare choice)
(declare lvar)
(declare lvar?)
(declare pair)
(declare lcons)
(declare run-constraints*)

(defn occurs-check [s u v]
  (let [v (walk s v)]
    (occurs-check-term v u s)))

(defn ext [s u v]
  (if (and *occurs-check* (occurs-check s u v))
    nil
    (ext-no-check s u v)))

(defn walk* [s v]
  (let [v (walk s v)]
    (walk-term v s)))

(defn unify [s u v]
  (if (identical? u v)
    s
    (let [u (walk s u)
          v (walk s v)]
      (if (identical? u v)
        s
        (unify-terms u v s)))))

(defn reify-lvar-name [s]
  (symbol (str "_." (count s))))

(defn -reify* [s v]
  (let [v (walk s v)]
    (reify-term v s)))

(defn -reify [s v]
  (let [v (walk* s v)]
    (walk* (-reify* empty-s v) v)))

(defn build [s u]
  (build-term u s))

(deftype Substitutions [s l cs]
  Object
  (equals [this o]
    (or (identical? this o)
        (and (.. this getClass (isInstance o))
             (= s ^clojure.lang.PersistentHashMap (.s ^Substitutions o)))))
  (toString [_] (prn s))

  clojure.lang.Counted
  (count [this] (count s))

  ISubstitutions
  (ext-no-check [this u v]
    (Substitutions. (assoc s u v)
                    (cons (pair u v) l)
                    cs))

  (walk [this v]
    (loop [lv v [v vp] (find s v)]
      (cond
       (nil? v) lv
       (not (lvar? vp)) vp
       :else (recur vp (find s vp)))))

  ISubstitutionsCLP
  (update [this x v]
    ((run-constraints* (if (lvar? v) [x v] [x]) cs)
     (if *occurs-check*
       (ext this x v)
       (ext-no-check this x v))))

  IBind
  (bind [this g]
    (g this))
  IMPlus
  (mplus [this f]
    (choice this f))
  ITake
  (take* [this] this))

(defn- ^Substitutions make-s
  ([] (Substitutions. {} () (make-cs)))
  ([m] (Substitutions. m () (make-cs)))
  ([m l] (Substitutions. m l (make-cs)))
  ([m l cs] (Substitutions. m l cs)))

(def ^Substitutions empty-s (make-s))
(def empty-f (fn []))

(defn- subst? [x]
  (instance? Substitutions x))

(defn ^Substitutions to-s [v]
  (let [s (reduce (fn [m [k v]] (assoc m k v)) {} v)
        l (reduce (fn [l [k v]] (cons (Pair. k v) l)) '() v)]
    (make-s s l (make-cs))))

;; =============================================================================
;; Logic Variables

(deftype LVar [name hash meta]
  clojure.lang.IObj
  (meta [this]
    meta)
  (withMeta [this new-meta]
    (LVar. name hash meta))
  Object
  (toString [_] (str "<lvar:" name ">"))
  (equals [this o]
    (and (.. this getClass (isInstance o))
         (let [^LVar o o]
           (identical? name (.name o)))))
  (hashCode [_] hash)
  IUnifyTerms
  (unify-terms [u v s]
    (unify-with-lvar v u s))
  IUnifyWithNil
  (unify-with-nil [v u s]
    (ext-no-check s v u))
  IUnifyWithObject
  (unify-with-object [v u s]
    (ext s v u))
  IUnifyWithLVar
  (unify-with-lvar [v u s]
    (ext-no-check s u v))
  IUnifyWithLSeq
  (unify-with-lseq [v u s]
    (ext s v u))
  IUnifyWithSequential
  (unify-with-seq [v u s]
    (ext s v u))
  IUnifyWithMap
  (unify-with-map [v u s]
    (ext s v u))
  IUnifyWithSet
  (unify-with-set [v u s]
    (ext s v u))
  IReifyTerm
  (reify-term [v s]
    (if *reify-vars*
      (ext s v (reify-lvar-name s))
      (ext s v (:name meta))))
  IWalkTerm
  (walk-term [v s] v)
  IOccursCheckTerm
  (occurs-check-term [v x s] (= (walk s v) x))
  IBuildTerm
  (build-term [u s]
    (let [^Substitutions s s
          m (.s s)
          l (.l s)
          cs (.cs s)
          lv (lvar 'ignore) ]
      (if (contains? m u)
        s
        (make-s (assoc m u lv)
                (cons (Pair. u lv) l)
                cs)))))

(defn ^LVar lvar
  ([]
     (let [name (str (. clojure.lang.RT (nextID)))]
       (LVar. name (.hashCode name) nil)))
  ([name]
     (let [oname name
           name (str name "_" (. clojure.lang.RT (nextID)))]
       (LVar. name (.hashCode name) nil)))
  ([name cs]
     (let [oname name
           name (str name "_" (. clojure.lang.RT (nextID)))]
       (LVar. name (.hashCode name) cs))))

(defmethod print-method LVar [x ^Writer writer]
  (.write writer (str "<lvar:" (.name ^LVar x) ">")))

(defn lvar? [x]
  (instance? LVar x))

;; =============================================================================
;; LCons

(defmacro umi
  [& args]
  (if (resolve 'unchecked-multiply-int)
    `(unchecked-multiply-int ~@args)
    `(unchecked-multiply ~@args)))

(defmacro uai
  [& args]
  (if (resolve 'unchecked-add-int)
    `(unchecked-add-int ~@args)
    `(unchecked-add ~@args)))

(defprotocol LConsSeq
  (lfirst [this])
  (lnext [this]))

;; TODO: clean up the printing code

(defprotocol LConsPrint
  (toShortString [this]))

(declare lcons?)

(deftype LCons [a d ^{:unsynchronized-mutable true :tag int} cache meta]
  clojure.lang.IObj
  (meta [this]
    meta)
  (withMeta [this new-meta]
    (LCons. a d cache new-meta))
  LConsSeq
  (lfirst [_] a)
  (lnext [_] d)
  LConsPrint
  (toShortString [this]
    (cond
     (.. this getClass (isInstance d)) (str a " " (toShortString d))
     :else (str a " . " d )))
  Object
  (toString [this] (cond
                    (.. this getClass (isInstance d))
                      (str "(" a " " (toShortString d) ")")
                    :else (str "(" a " . " d ")")))
  (equals [this o]
    (or (identical? this o)
        (and (.. this getClass (isInstance o))
             (loop [me this
                    you o]
               (cond
                (nil? me) (nil? you)
                (lvar? me) true
                (lvar? you) true
                (and (lcons? me) (lcons? you))
                  (let [mef  (lfirst me)
                        youf (lfirst you)]
                    (and (or (= mef youf)
                             (lvar? mef)
                             (lvar? youf))
                         (recur (lnext me) (lnext you))))
                :else (= me you))))))

  (hashCode [this]
    (if (= cache -1)
      (do
        (set! cache (uai (umi (int 31) (clojure.lang.Util/hash d))
                         (clojure.lang.Util/hash a)))
        cache)
      cache))
  IUnifyTerms
  (unify-terms [u v s]
    (unify-with-lseq v u s))
  IUnifyWithNil
  (unify-with-nil [v u s] false)
  IUnifyWithObject
  (unify-with-object [v u s] false)
  IUnifyWithLSeq
  (unify-with-lseq [v u s]
    (loop [u u v v s s]
      (if (lvar? u)
        (unify s u v)
        (cond
         (lvar? v) (unify s v u)
         (and (lcons? u) (lcons? v))
           (if-let [s (unify s (lfirst u) (lfirst v))]
             (recur (lnext u) (lnext v) s)
             false)
         :else (unify s u v)))))
  IUnifyWithSequential
  (unify-with-seq [v u s]
    (unify-with-lseq u v s))
  IUnifyWithMap
  (unify-with-map [v u s] false)
  IUnifyWithSet
  (unify-with-set [v u s] false)
  IReifyTerm
  (reify-term [v s]
    (loop [v v s s]
      (if (lcons? v)
        (recur (lnext v) (-reify* s (lfirst v)))
        (-reify* s v))))
  ;; TODO: no way to make this non-stack consuming w/o a lot more thinking
  ;; we could use continuation passing style and trampoline
  IWalkTerm
  (walk-term [v s]
    (lcons (walk* s (lfirst v))
           (walk* s (lnext v))))
  IOccursCheckTerm
  (occurs-check-term [v x s]
    (loop [v v x x s s]
      (if (lcons? v)
        (or (occurs-check s x (lfirst v))
            (recur (lnext v) x s))
        (occurs-check s x v))))
  IBuildTerm
  (build-term [u s]
    (loop [u u s s]
      (if (lcons? u)
        (recur (lnext u) (build s (lfirst u)))
        (build s u)))))

(defmethod print-method LCons [x ^Writer writer]
  (.write writer (str x)))

(defn lcons
  "Constructs a sequence a with an improper tail d if d is a logic variable."
  [a d]
  (if (or (coll? d) (nil? d))
    (cons a (seq d))
    (LCons. a d -1 nil)))

(defn lcons? [x]
  (instance? LCons x))

(defmacro llist
  "Constructs a sequence from 2 or more arguments, with the last argument as the
   tail. The tail is improper if the last argument is a logic variable."
  ([f s] `(lcons ~f ~s))
  ([f s & rest] `(lcons ~f (llist ~s ~@rest))))

;; =============================================================================
;; Unification

;; TODO : a lot of cascading ifs need to be converted to cond

(extend-protocol IUnifyTerms
  nil
  (unify-terms [u v s]
    (unify-with-nil v u s)))

(extend-type Object
  IUnifyTerms
  (unify-terms [u v s]
    (unify-with-object v u s)))

(extend-protocol IUnifyTerms
  clojure.lang.Sequential
  (unify-terms [u v s]
    (unify-with-seq v u s)))

(extend-protocol IUnifyTerms
  clojure.lang.IPersistentMap
  (unify-terms [u v s]
    (unify-with-map v u s)))

(extend-protocol IUnifyTerms
  clojure.lang.IPersistentSet
  (unify-terms [u v s]
    (unify-with-set v u s)))

;; -----------------------------------------------------------------------------
;; Unify nil with X

(extend-protocol IUnifyWithNil
  nil
  (unify-with-nil [v u s] s))

(extend-type Object
  IUnifyWithNil
  (unify-with-nil [v u s] false))

;; -----------------------------------------------------------------------------
;; Unify Object with X

(extend-protocol IUnifyWithObject
  nil
  (unify-with-object [v u s] false))

(extend-type Object
  IUnifyWithObject
  (unify-with-object [v u s]
    (cond
     (= u v) s
     (domain? u) (unify-with-dom v u s)
     :else false)))

;; -----------------------------------------------------------------------------
;; Unify LVar with X

(extend-protocol IUnifyWithLVar
  nil
  (unify-with-lvar [v u s] (ext-no-check s u v)))

(extend-type Object
  IUnifyWithLVar
  (unify-with-lvar [v u s]
    (ext s u v)))

;; -----------------------------------------------------------------------------
;; Unify Domain with X

(extend-protocol IUnifyWithDomain
  nil
  (unify-with-dom [v u s] false)

  Object
  (unify-with-dom [v u s]
    (if (domain? v)
      (if-let [i (intersection v u)]
        s)
      false)))

;; -----------------------------------------------------------------------------
;; Unify LCons with X

(extend-protocol IUnifyWithLSeq
  nil
  (unify-with-lseq [v u s] false))

(extend-type Object
  IUnifyWithLSeq
  (unify-with-lseq [v u s] false))

(extend-protocol IUnifyWithLSeq
  clojure.lang.Sequential
  (unify-with-lseq [v u s]
    (loop [u u v v s s]
      (if (seq v)
        (if (lcons? u)
          (if-let [s (unify s (lfirst u) (first v))]
            (recur (lnext u) (next v) s)
            false)
          (unify s u v))
        (if (lvar? u)
          (unify s u '())
          false)))))

;; -----------------------------------------------------------------------------
;; Unify Sequential with X

(extend-protocol IUnifyWithSequential
  nil
  (unify-with-seq [v u s] false))

(extend-type Object
  IUnifyWithSequential
  (unify-with-seq [v u s] false))

(extend-protocol IUnifyWithSequential
  clojure.lang.Sequential
  (unify-with-seq [v u s]
    (loop [u u v v s s]
      (if (seq u)
        (if (seq v)
          (if-let [s (unify s (first u) (first v))]
            (recur (next u) (next v) s)
            false)
          false)
        (if (seq v) false s)))))

;; -----------------------------------------------------------------------------
;; Unify IPersistentMap with X

(extend-protocol IUnifyWithMap
  nil
  (unify-with-map [v u s] false))

(extend-type Object
  IUnifyWithMap
  (unify-with-map [v u s] false))

(extend-protocol IUnifyWithMap
  clojure.lang.IPersistentMap
  (unify-with-map [v u s]
    (let [ks (keys u)]
      (loop [ks ks u u v v s s]
        (if (seq ks)
          (let [kf (first ks)
                vf (get v kf ::not-found)]
            (if (= vf ::not-found)
              false
              (if-let [s (unify s (get u kf) vf)]
                (recur (next ks) (dissoc u kf) (dissoc v kf) s)
                false)))
          (if (seq v)
            false
            s))))))

;; -----------------------------------------------------------------------------
;; Unify IPersistentSet with X

(extend-protocol IUnifyWithSet
  nil
  (unify-with-set [v u s] false))

(extend-type Object
  IUnifyWithSet
  (unify-with-set [v u s] false))

;; TODO : improve speed, the following takes 890ms
;; 
;; (let [a (lvar 'a)
;;       b (lvar 'b)
;;       c (lvar 'c)
;;       d (lvar 'd)
;;       s1 #{a b 3 4 5}
;;       s2 #{1 2 3 c d}]
;;     (dotimes [_ 10]
;;       (time
;;        (dotimes [_ 1e5]
;;          (.s (unify empty-s s1 s2))))))

(extend-protocol IUnifyWithSet
  clojure.lang.IPersistentSet
  (unify-with-set [v u s]
    (loop [u u v v ulvars [] umissing []]
      (if (seq u)
        (if (seq v)
          (let [uf (first u)]
            (if (lvar? uf)
              (recur (disj u uf) v (conj ulvars uf) umissing)
              (if (contains? v uf)
                (recur (disj u uf) (disj v uf) ulvars umissing)
                (recur (disj u uf) v ulvars (conj umissing uf)))))
          false)
        (if (seq v)
          (if (seq ulvars)
            (loop [v v vlvars [] vmissing []]
              (if (seq v)
                (let [vf (first v)]
                  (if (lvar? vf)
                    (recur (disj v vf) (conj vlvars vf) vmissing)
                    (recur (disj v vf) vlvars (conj vmissing vf))))
                (unify s (concat ulvars umissing)
                       (concat vmissing vlvars))))
            false)
          s)))))

;; =============================================================================
;; Reification

(extend-protocol IReifyTerm
  nil
  (reify-term [v s] s))

(extend-type Object
  IReifyTerm
  (reify-term [v s] s))

(extend-protocol IReifyTerm
  clojure.lang.IPersistentCollection
  (reify-term [v s]
    (loop [v v s s]
      (if (seq v)
        (recur (next v) (-reify* s (first v)))
        s))))

;; =============================================================================
;; Walk Term

(extend-protocol IWalkTerm
  nil
  (walk-term [v s] nil))

(extend-type Object
  IWalkTerm
  (walk-term [v s] v))

(extend-protocol IWalkTerm
  clojure.lang.ISeq
  (walk-term [v s]
    (with-meta
      (map #(walk* s %) v)
      (meta v))))

(extend-protocol IWalkTerm
  clojure.lang.IPersistentVector
  (walk-term [v s]
    (with-meta
      (loop [v v r (transient [])]
        (if (seq v)
          (recur (next v) (conj! r (walk* s (first v))))
          (persistent! r)))
      (meta v))))

(extend-protocol IWalkTerm
  clojure.lang.IPersistentMap
  (walk-term [v s]
    (with-meta
      (loop [v v r (transient {})]
        (if (seq v)
          (let [[vfk vfv] (first v)]
            (recur (next v) (assoc! r vfk (walk* s vfv))))
          (persistent! r)))
      (meta v))))

(extend-protocol IWalkTerm
  clojure.lang.IPersistentSet
  (walk-term [v s]
    (with-meta
      (loop [v v r #{}]
        (if (seq v)
          (recur (next v) (conj r (walk* s (first v))))
          r))
      (meta v))))

;; =============================================================================
;; Occurs Check Term

(extend-protocol IOccursCheckTerm
  nil
  (occurs-check-term [v x s] false))

(extend-type Object
  IOccursCheckTerm
  (occurs-check-term [v x s] false))

(extend-protocol IOccursCheckTerm
  clojure.lang.IPersistentCollection
  (occurs-check-term [v x s]
    (loop [v v x x s s]
      (if (seq v)
        (or (occurs-check s x (first v))
            (recur (next v) x s))
        false))))

;; =============================================================================
;; Build Term

(extend-protocol IBuildTerm
  nil
  (build-term [u s] s))

(extend-type Object
  IBuildTerm
  (build-term [u s] s))

(extend-protocol IBuildTerm
  clojure.lang.ISeq
  (build-term [u s]
    (reduce build s u)))

;; =============================================================================
;; Goals and Goal Constructors

(defmacro bind*
  ([a g] `(bind ~a ~g))
  ([a g & g-rest]
     `(bind* (bind ~a ~g) ~@g-rest)))

(defmacro mplus*
  ([e] e)
  ([e & e-rest]
     `(mplus ~e (fn [] (mplus* ~@e-rest)))))

(defmacro -inc [& rest]
  `(fn -inc [] ~@rest))

(extend-type Object
  ITake
  (take* [this] this))

;; TODO: Choice always holds a as a list, can we just remove that?

(deftype Choice [a f]
  IBind
  (bind [this g]
    (mplus (g a) (-inc (bind f g))))
  IMPlus
  (mplus [this fp]
    (Choice. a (fn [] (mplus (fp) f))))
  ITake
  (take* [this]
    (lazy-seq (cons (first a) (lazy-seq (take* f))))))

(defn ^Choice choice [a f]
  (Choice. a f))

;; -----------------------------------------------------------------------------
;; MZero

(extend-protocol IBind
  nil
  (bind [_ g] nil))

(extend-protocol IMPlus
  nil
  (mplus [_ b] b))

(extend-protocol ITake
  nil
  (take* [_] '()))

;; -----------------------------------------------------------------------------
;; Unit

(extend-type Object
  IMPlus
  (mplus [this f]
    (Choice. this f)))

;; -----------------------------------------------------------------------------
;; Inc

(extend-type clojure.lang.Fn
  IBind
  (bind [this g]
    (-inc (bind (this) g)))
  IMPlus
  (mplus [this f]
    (-inc (mplus (f) this)))
  ITake
  (take* [this] (lazy-seq (take* (this)))))

;; =============================================================================
;; Syntax

(defn succeed
  "A goal that always succeeds."
  [a] a)

(defn fail
  "A goal that always fails."
  [a] nil)

(def s# succeed)

(def u# fail)

#_(defmacro ==
  "A goal that attempts to unify terms u and v."
  [u v]
  `(fn [a#]
     (if-let [b# (unify a# ~u ~v)]
       b# nil)))

(declare ==-c)

(defn ==
  "A goal that attempts to unify terms u and v."
  [u v]
  (==-c u v))

(defn- bind-conde-clause [a]
  (fn [g-rest]
    `(bind* ~a ~@g-rest)))

(defn- bind-conde-clauses [a clauses]
  (map (bind-conde-clause a) clauses))

(defmacro conde
  "Logical disjunction of the clauses. The first goal in
  a clause is considered the head of that clause. Interleaves the
  execution of the clauses."
  [& clauses]
  (let [a (gensym "a")]
    `(fn [~a]
       (-inc
        (mplus* ~@(bind-conde-clauses a clauses))))))

(defn- lvar-bind [sym]
  ((juxt identity
         (fn [s] `(lvar '~s))) sym))

(defn- lvar-binds [syms]
  (mapcat lvar-bind syms))

(defmacro fresh
  "Creates fresh variables. Goals occuring within form a logical 
  conjunction."
  [[& lvars] & goals]
  `(fn [a#]
     (-inc
      (let [~@(lvar-binds lvars)]
        (bind* a# ~@goals)))))

(defmacro solve [& [n [x] & goals]]
  `(let [xs# (take* (fn []
                      ((fresh [~x] ~@goals
                         (fn [a#]
                           (cons (-reify a# ~x) '()))) ;; TODO: do we need this?
                       empty-s)))]
     (if ~n
       (take ~n xs#)
       xs#)))

(defmacro run
  "Executes goals until a maximum of n results are found."
  [n & goals]
  `(doall (solve ~n ~@goals)))

(defmacro run*
  "Executes goals until results are exhausted."
  [& goals]
  `(run false ~@goals))

(defmacro run-nc
  "Executes goals until a maximum of n results are found. Does not 
   occurs-check."
  [& [n & goals]]
  `(binding [*occurs-check* false]
     (run ~n ~@goals)))

(defmacro run-nc*
  "Executes goals until results are exhausted. Does not occurs-check."
  [& goals]
  `(run-nc false ~@goals))

(defmacro lazy-run
  "Lazily executes goals until a maximum of n results are found."
  [& [n & goals]]
  `(solve ~n ~@goals))

(defmacro lazy-run*
  "Lazily executes goals until results are exhausted."
  [& goals]
  `(solve false ~@goals))

(defmacro all
  "Like fresh but does does not create logic variables."
  ([] `clojure.core.logic/s#)
  ([& goals] `(fn [a#] (bind* a# ~@goals))))

;; =============================================================================
;; Debugging

(defmacro log [& s]
  "Goal for println"
  `(fn [a#]
     (println ~@s)
     a#))

(defmacro trace-s []
  "Goal that prints the current substitution"
  `(fn [a#]
     (println (str a#))
     a#))

(defn trace-lvar [a lvar]
  `(println (format "%5s = %s" (str '~lvar) (-reify ~a ~lvar))))

(defmacro trace-lvars
  "Goal for tracing the values of logic variables."
  [title & lvars]
  (let [a (gensym "a")]
    `(fn [~a]
       (println ~title)
       ~@(map (partial trace-lvar a) lvars)
       ~a)))

;; =============================================================================
;; Easy Unification

(defn- lvarq-sym? [s]
  (and (symbol? s) (= (first (str s)) \?)))

(defn- proc-lvar [lvar-expr store]
  (let [v (if-let [u (@store lvar-expr)]
            u
            (lvar lvar-expr))]
    (swap! store conj [lvar-expr v])
    v))

(defn- lcons-expr? [expr]
  (and (seq? expr) (some '#{.} (set expr))))

(declare prep*)

(defn- replace-lvar [store]
  (fn [expr]
    (if (lvarq-sym? expr)
      (proc-lvar expr store)
      (if (lcons-expr? expr)
        (prep* expr store)
        expr))))

(defn- prep*
  ([expr store] (prep* expr store false false))
  ([expr store lcons?] (prep* expr store lcons? false))
  ([expr store lcons? last?]
     (let [expr (if (and last? (seq expr))
                  (first expr)
                  expr)]
       (cond
        (lvarq-sym? expr) (proc-lvar expr store)
        (seq? expr) (if (or lcons? (lcons-expr? expr))
                      (let [[f & n] expr
                            skip (= f '.)
                            tail (prep* n store lcons? skip)]
                        (if skip
                          tail
                          (lcons (prep* f store) tail)))
                      (postwalk (replace-lvar store) expr))
        :else expr))))

(defn prep
  "Prep a quoted expression. All symbols preceded by ? will
  be replaced with logic vars."
  [expr]
  (let [lvars (atom {})
        prepped (if (lcons-expr? expr)
                  (prep* expr lvars true)
                  (postwalk (replace-lvar lvars) expr))]
    (with-meta prepped {:lvars @lvars})))

(defn unifier*
  "Unify the terms u and w."
  ([u w]
     (first
      (run* [q]
        (== u w)
        (== u q))))
  ([u w & ts]
     (apply unifier* (unifier* u w) ts)))

(defn binding-map*
  "Return the binding map that unifies terms u and w.
  u and w should prepped terms."
  ([u w]
     (let [lvars (merge (-> u meta :lvars)
                        (-> w meta :lvars))
           s (unify empty-s u w)]
       (when s
         (into {} (map (fn [[k v]]
                         [k (-reify s v)])
                       lvars)))))
  ([u w & ts]
     (apply binding-map* (binding-map* u w) ts)))

(defn unifier
  "Unify the terms u and w. Will prep the terms."
  ([u w]
     {:pre [(not (lcons? u))
            (not (lcons? w))]}
     (let [up (prep u)
           wp (prep w)]
       (unifier* up wp)))
  ([u w & ts]
     (apply unifier (unifier u w) ts)))

(defn binding-map
  "Return the binding map that unifies terms u and w.
  Will prep the terms."
  ([u w]
     {:pre [(not (lcons? u))
            (not (lcons? w))]}
     (let [up (prep u)
           wp (prep w)]
       (binding-map* up wp)))
  ([u w & ts]
     (apply binding-map (binding-map u w) ts)))

;; =============================================================================
;; Non-relational goals

;; =============================================================================
;; project

(defn- project-binding [s]
  (fn [var]
    `(~var (walk* ~s ~var))))

(defn- project-bindings [vars s]
  (reduce concat (map (project-binding s) vars)))

(defmacro project
  "Extract the values bound to the specified logic vars. Non-relational."
  [[& vars] & goals]
  (let [a (gensym "a")]
    `(fn [~a]
       (let [~@(project-bindings vars a)]
         ((fresh []
            ~@goals) ~a)))))

(defmacro pred
  "Check a predicate against the value logic var. Non-relational."
  [v f]
  `(project [~v]
     (== (~f ~v) true)))

(defmacro is
  "Set the value of a var to value of another var with the operation
   applied. Non-relational."
  [u v op]
  `(project [~v]
     (== ~u (~op ~v))))

;; =============================================================================
;; conda (soft-cut), condu (committed-choice)
;;
;; conda once a line succeeds no others are tried
;; condu a line can succeed only one time

;; TODO : conda and condu should probably understanding logging

(defprotocol IIfA
  (ifa [b gs c]))

(defprotocol IIfU
  (ifu [b gs c]))

;; TODO : if -> when

(defmacro ifa*
  ([])
  ([[e & gs] & grest]
     `(ifa ~e [~@gs]
           ~(if (seq grest)
              `(delay (ifa* ~@grest))
              nil))))

(defmacro ifu*
  ([])
  ([[e & gs] & grest]
     `(ifu ~e [~@gs]
           ~(if (seq grest)
              `(delay (ifu* ~@grest))
              nil))))

(extend-protocol IIfA
  nil
  (ifa [b gs c]
       (when c
         (force c))))

(extend-protocol IIfU
  nil
  (ifu [b gs c]
       (when c
         (force c))))

(extend-type Substitutions
  IIfA
  (ifa [b gs c]
       (loop [b b [g0 & gr] gs]
         (if g0
           (when-let [b (g0 b)]
             (recur b gr))
           b))))

(extend-type Substitutions
  IIfU
  (ifu [b gs c]
       (loop [b b [g0 & gr] gs]
         (if g0
           (when-let [b (g0 b)]
             (recur b gr))
           b))))

(extend-type clojure.lang.Fn
  IIfA
  (ifa [b gs c]
       (-inc (ifa (b) gs c))))

(extend-type clojure.lang.Fn
  IIfU
  (ifu [b gs c]
       (-inc (ifu (b) gs c))))

(extend-protocol IIfA
  Choice
  (ifa [b gs c]
       (reduce bind b gs)))

;; TODO: Choice always holds a as a list, can we just remove that?
(extend-protocol IIfU
  Choice
  (ifu [b gs c]
       (reduce bind (.a ^Choice b) gs)))

(defn- cond-clauses [a]
  (fn [goals]
    `((~(first goals) ~a) ~@(rest goals))))

(defmacro conda
  "Soft cut. Once the head of a clause has succeeded
  all other clauses will be ignored. Non-relational."
  [& clauses]
  (let [a (gensym "a")]
    `(fn [~a]
       (ifa* ~@(map (cond-clauses a) clauses)))))

(defmacro condu
  "Committed choice. Once the head (first goal) of a clause 
  has succeeded, remaining goals of the clause will only
  be run once. Non-relational."
  [& clauses]
  (let [a (gensym "a")]
    `(fn [~a]
       (ifu* ~@(map (cond-clauses a) clauses)))))

(defn onceo [g] (condu (g)))

;; =============================================================================
;; copy-term

(defn copy-term
  "Copies a term u into v. Non-relational."
  [u v]
  (project [u]
    (== (walk* (build empty-s u) u) v)))

;; =============================================================================
;; lvar nonlvar

(defmacro lvaro
  "Goal to test whether a logic var is ground. Non-relational."
  [v]
  `(fn [a#]
     (if (lvar? (walk a# ~v))
       a# nil)))

(defmacro nonlvaro
  "Goal to test whether a logic var is ground. Non-relational."
  [v]
  `(fn [a#]
     (if (not (lvar? (walk a# ~v)))
       a# nil)))

;; =============================================================================
;; Pattern matching

(defn- warn [& msg]
  (binding [*out* *err*]
    (apply println "WARNING:" msg)))

(declare p->term)

(defn- lcons-p? [p]
  (and (coll? p)
       (not (nil? (some '#{.} p)))))

(defn- p->llist [p]
  `(llist
    ~@(map p->term
           (remove #(contains? '#{.} %) p))))

(defn- p->term [p]
  (cond
   (= p '_) `(lvar)
   (lcons-p? p) (p->llist p)
   (and (coll? p) (not= (first p) 'quote))
     (cond
      ;; support simple expressions
      (list? p) p
      ;; preserve original collection type
      :else (let [ps (map p->term p)]
              (cond
               (instance? clojure.lang.MapEntry p) (into [] ps)
               :else (into (empty p) ps))))
   :else p))

(defn- lvar-sym? [s]
  (and (symbol? s)
       (not= s '.)
       (not (contains? *locals* s))))

(defn- extract-vars
  ([p]
     (set (cond
           (lvar-sym? p) [p]           
           (coll? p) (let [p (if (seq? p) (rest p) p)]
                       (filter lvar-sym? (flatten p)))
           :else nil)))
  ([p seen]
     (set/difference (extract-vars p) (set seen))))

(defn- fresh-expr? [cs]
  (= (first cs) `fresh))

(defn- ex
  ([vs t a]
     `(fresh [~@vs]
        (== ~t ~a)))
  ([vs t a exprs]
     (if (fresh-expr? exprs)
       `(fresh [~@vs]
          (== ~t ~a)
          ~exprs)
       `(fresh [~@vs]
          (== ~t ~a)
          ~@exprs))))

(defn- ex* [[[p a :as pa] & par] exprs seen]
  (let [t (p->term p)
        vs (extract-vars p seen)
        seen (reduce conj seen vs)]
    (cond
     (nil? pa) exprs
     (= p '_) (ex* par exprs seen)
     (empty? par) (if exprs
                    (ex vs t a exprs)
                    (ex vs t a))
     :else (let [r (ex* par exprs seen)]
             (if r
               (ex vs t a r)
               (ex vs t a))))))

(defn- all-blank? [p]
  (every? #(= % '_) p))

(defn- handle-clause [as]
  (when-not (vector? as)
    (throw (Exception. (str "Expecting vector of arguments, instead " as))))
  (fn [[p & exprs]]
    (when-not (vector? p)
      (throw (Exception. (str "Expecting vector of matches, instead " p))))
    (when-not (= (count p) (count as))
      (warn "Differing number of matches. Matching" p "against" as))
    (let [pas (partition 2 (interleave p as))
          r (ex* pas exprs #{})]
      (if (all-blank? p)
        r
        (list r)))))

(defn- handle-clauses [t as cs]
  `(~t
    ~@(doall (map (handle-clause as) cs))))

;; name-with-attributes by Konrad Hinsen, from clojure.contrib.def
(defn- name-with-attributes
  "To be used in macro definitions.
   Handles optional docstrings and attribute maps for a name to be defined
   in a list of macro arguments. If the first macro argument is a string
   it is added as a docstring to name and removed from the macro argument
   list. If afterwards the first macro argument is a map, its entries are
   added to the name's metadata map and the map is removed from the
   macro argument list. The return value is a vector containing the name
   with its extended metadata map and the list of unprocessed macro
   arguments."
  [name macro-args]
  (let [[docstring macro-args] (if (string? (first macro-args))
                                 [(first macro-args) (next macro-args)]
                                 [nil macro-args])
    [attr macro-args]          (if (map? (first macro-args))
                                 [(first macro-args) (next macro-args)]
                                 [{} macro-args])
    attr                       (if docstring
                                 (assoc attr :doc docstring)
                                 attr)
    attr                       (if (meta name)
                                 (conj (meta name) attr)
                                 attr)]
    [(with-meta name attr) macro-args]))

(declare tabled)

(defn env-locals [& syms]
  (disj (set (apply concat syms)) '_))

(defmacro defnm [t n & rest]
  (let [[n [as & cs]] (name-with-attributes n rest)]
    (binding [*locals* (env-locals as (keys &env))]
     (if-let [tabled? (-> n meta :tabled)]
       `(def ~n (tabled [~@as] ~(handle-clauses t as cs)))
       `(defn ~n [~@as] ~(handle-clauses t as cs))))))

;; =============================================================================
;; Useful goals

(defn nilo
  "A relation where a is nil"
  [a]
  (== nil a))

(defn emptyo
  "A relation where a is the empty list"
  [a]
  (== '() a))

(defn conso
  "A relation where l is a collection, such that a is the first of l 
  and d is the rest of l"
  [a d l]
  (== (lcons a d) l))

(defn firsto
  "A relation where l is a collection, such that a is the first of l"
  [l a]
  (fresh [d]
    (conso a d l)))

(defn resto
  "A relation where l is a collection, such that d is the rest of l"
  [l d]
  (fresh [a]
    (== (lcons a d) l)))

;; =============================================================================
;; Goal sugar syntax

(defmacro defne
  "Define a goal fn. Supports pattern matching. All
   patterns will be tried. See conde."
  [& rest]
  `(defnm conde ~@rest))

(defmacro matche
  "Pattern matching macro. All patterns will be tried.
  See conde."
  [xs & cs]
  (binding [*locals* (env-locals xs (keys &env))]
    (handle-clauses `conde xs cs)))

;; -----------------------------------------------------------------------------
;; defnu, defna, matcha, matchu

;; TODO: we need to rethink defna and defnu, the unification comes first
;; the *question* should come first

(defmacro defna
  "Define a soft cut goal. See conda."
  [& rest]
  `(defnm conda ~@rest))

(defmacro defnu
  "Define a committed choice goal. See condu."
  [& rest]
  `(defnm condu ~@rest))

(defmacro matcha
  "Define a soft cut pattern match. See conda."
  [xs & cs]
  (binding [*locals* (env-locals xs (keys &env))]
    (handle-clauses `conda xs cs)))

(defmacro matchu
  "Define a committed choice goal. See condu."
  [xs & cs]
  (binding [*locals* (env-locals xs (keys &env))]
    (handle-clauses `condu xs cs)))

;; =============================================================================
;; More convenient goals

(defne membero 
  "A relation where l is a collection, such that l contains x"
  [x l]
  ([_ [x . tail]])
  ([_ [head . tail]]
     (membero x tail)))

(defne appendo 
  "A relation where x, y, and z are proper collections, 
  such that z is x appended to y"
  [x y z]
  ([() _ y])
  ([[a . d] _ [a . r]] (appendo d y r)))

;; =============================================================================
;; Rel

(defn to-stream [aseq]
  (when (seq aseq)
    (choice (first aseq)
            (let [aseq (drop-while #(or (nil? %) (false? %)) (next aseq))]
              (fn [] (to-stream aseq))))))

(defmacro def-arity-exc-helper []
  (try
    (Class/forName "clojure.lang.ArityException")
    `(defn arity-exc-helper [~'name ~'n]
       (fn [~'& ~'args]
         (throw (clojure.lang.ArityException. ~'n (str ~'name)))))
    (catch java.lang.ClassNotFoundException e
     `(defn ~'arity-exc-helper [~'name ~'n]
        (fn [~'& ~'args]
          (throw
           (java.lang.IllegalArgumentException.
            (str "Wrong number of args (" ~'n ") passed to:" ~'name))))))))

(def-arity-exc-helper)

(defn- sym-helper [prefix n]
  (symbol (str prefix n)))

(def f-sym (partial sym-helper "f"))
(def a-sym (partial sym-helper "a"))

(defn- ->sym [& args]
  (symbol (apply str args)))

(defn- defrel-helper [name arity args]
  (let [r (range 1 (+ arity 2))
        arity-excs (fn [n] `(arity-exc-helper '~name ~n))]
    (if (seq args)
      `(do
         (def ~name
           (.withMeta
            (~'clojure.core.logic.Rel.
             '~name (atom {}) nil ~@(map arity-excs r))
            {:ns ~'*ns*}))
         (extend-rel ~name ~@args))
      `(def ~name
         (.withMeta
          (~'clojure.core.logic.Rel. '~name (atom {}) nil ~@(map arity-excs r))
          {:ns ~'*ns*})))))

(defmacro def-apply-to-helper [n]
  (let [r (range 1 (clojure.core/inc n))
        args (map a-sym r)
        arg-binds (fn [n]
                    (mapcat (fn [a]
                              `(~a (first ~'arglist)
                                   ~'arglist (next ~'arglist)))
                            (take n args)))
        case-clause (fn [n]
                      `(~n (let [~@(arg-binds (dec n))]
                            (.invoke ~'ifn ~@(take (dec n) args)
                                     (clojure.lang.Util/ret1
                                      (first ~'arglist) nil)))))]
   `(defn ~'apply-to-helper
      [~(with-meta 'ifn {:tag clojure.lang.IFn}) ~'arglist]
      (case (clojure.lang.RT/boundedLength ~'arglist 20)
            ~@(mapcat case-clause r)))))

(def-apply-to-helper 20)

(defprotocol IRel
  (setfn [this arity f])
  (indexes-for [this arity])
  (add-indexes [this arity index]))

;; TODO: consider moving the set/indexes inside Rel, perf implications?

(defmacro RelHelper [arity]
  (let [r (range 1 (+ arity 2))
        fs (map f-sym r)
        mfs (map #(with-meta % {:volatile-mutable true :tag clojure.lang.IFn})
                 fs)
        create-sig (fn [n]
                     (let [args (map a-sym (range 1 (clojure.core/inc n)))]
                       `(invoke [~'_ ~@args]
                                  (~(f-sym n) ~@args))))
        set-case (fn [[f arity]]
                   `(~arity (set! ~f ~'f)))]
    `(do
       (deftype ~'Rel [~'name ~'indexes ~'meta
                       ~@mfs]
         clojure.lang.IObj
         (~'withMeta [~'_ ~'meta]
           (~'Rel. ~'name ~'indexes ~'meta ~@fs))
         (~'meta [~'_]
           ~'meta)
         clojure.lang.IFn
         ~@(map create-sig r)
         (~'applyTo [~'this ~'arglist]
            (~'apply-to-helper ~'this ~'arglist))
         ~'IRel
         (~'setfn [~'_ ~'arity ~'f]
           (case ~'arity
                 ~@(mapcat set-case (map vector fs r))))
         (~'indexes-for [~'_ ~'arity]
           ((deref ~'indexes) ~'arity))
         (~'add-indexes [~'_ ~'arity ~'index]
           (swap! ~'indexes assoc ~'arity ~'index)))
       (defmacro ~'defrel 
         "Define a relation for adding facts. Takes a name and some fields.
         Use fact/facts to add facts and invoke the relation to query it."
         [~'name ~'& ~'rest]
         (defrel-helper ~'name ~arity ~'rest)))))

(RelHelper 20)

(defn- index-sym [name arity o]
  (->sym name "_" arity "-" o "-index"))

(defn- set-sym [name arity]
  (->sym name "_" arity "-set"))

;; TODO: for arity greater than 20, we need to use rest args

(defn contains-lvar? [x]
  (some lvar? (tree-seq coll? seq x)))

(defmacro extend-rel [name & args]
  (let [arity (count args)
        r (range 1 (clojure.core/inc arity))
        as (map a-sym r)
        indexed (vec (filter (fn [[a i]]
                               (-> a meta :index))
                             (map vector
                                  args
                                  (range 1 (clojure.core/inc arity)))))
        check-lvar (fn [[o i]]
                     (let [a (a-sym i)]
                       `((not (clojure.core.logic/contains-lvar? (clojure.core.logic/walk* ~'a ~a)))
                         ((deref ~(index-sym name arity o)) (clojure.core.logic/walk* ~'a ~a)))))
        indexed-set (fn [[o i]]
                      `(def ~(index-sym name arity o) (atom {})))]
    (if (<= arity 20)
     `(do
        (def ~(set-sym name arity) (atom #{}))
        ~@(map indexed-set indexed)
        (add-indexes ~name ~arity '~indexed)
        (setfn ~name ~arity
               (fn [~@as]
                 (fn [~'a]
                   (let [set# (cond
                               ~@(mapcat check-lvar indexed)
                               :else (deref ~(set-sym name arity)))]
                     (to-stream
                      (->> set#
                           (map (fn [cand#]
                                  (when-let [~'a (clojure.core.logic/unify
                                                  ~'a [~@as] cand#)]
                                    ~'a)))
                           (remove nil?)))))))))))

;; TODO: Should probably happen in a transaction

(defn facts
  "Define a series of facts. Takes a vector of vectors where each vector
   represents a fact tuple, all with the same number of elements."
  ([rel [f :as tuples]] (facts rel (count f) tuples))
  ([^Rel rel arity tuples]
     (let [rel-ns (:ns (meta rel))
           rel-set (var-get (ns-resolve rel-ns (set-sym (.name rel) arity)))
           tuples (map vec tuples)]
       (swap! rel-set (fn [s] (into s tuples)))
       (let [indexes (indexes-for rel arity)]
         (doseq [[o i] indexes]
           (let [index (var-get (ns-resolve rel-ns (index-sym (.name rel) arity o)))]
             (let [indexed-tuples (map (fn [t]
                                         {(nth t (dec i)) #{t}})
                                       tuples)]
               (swap! index
                      (fn [i]
                        (apply merge-with set/union i indexed-tuples))))))))))

(defn fact
  "Add a fact to a relation defined with defrel."
  [rel & tuple]
  (facts rel [(vec tuple)]))

(defn difference-with
  "Returns a map that consists of the first map with the rest of the maps
   removed from it. When a key is found in the first map and a later map,
   the value from the later map will be combined with the value in the first
   map by calling (f val-in-first val-in-later). If this function returns nil
   then the key will be removed completely."
  [f & maps]
  (when (some identity maps)
    (let [empty-is-nil (fn [s] (if (empty? s) nil s))
          merge-entry (fn [m [k v]]
                         (if (contains? m k)
                           (if-let [nv (empty-is-nil (f (get m k) v))]
                             (assoc m k nv)
                             (dissoc m k))))
          merge-map (fn [m1 m2] (reduce merge-entry (or m1 {}) (seq m2)))]
      (reduce merge-map maps))))

(defn retractions
  "Retract a series of facts. Takes a vector of vectors where each vector
   represents a fact tuple, all with the same number of elements. It is not
   an error to retract a fact that isn't true."
  ([rel [f :as tuples]] (retractions rel (count f) tuples))
  ([^Rel rel arity tuples]
     (let [rel-ns (:ns (meta rel))
           rel-set (var-get (ns-resolve rel-ns (set-sym (.name rel) arity)))
           tuples (map vec tuples)]
       (swap! rel-set (fn [s] (remove #(some #{%} tuples) s)))
       (let [indexes (indexes-for rel arity)]
         (doseq [[o i] indexes]
           (let [index (var-get (ns-resolve rel-ns (index-sym (.name rel) arity o)))]
             (let [indexed-tuples (map (fn [t]
                                         {(nth t (dec i)) #{t}})
                                       tuples)]
               (swap! index
                      (fn [i]
                        (apply difference-with set/difference i indexed-tuples))))))))))

(defn retraction
  "Remove a fact from a relation defined with defrel."
  [rel & tuple]
  (retractions rel [(vec tuple)]))

;; =============================================================================
;; Tabling

;; See - William Byrd "Relational Programming in miniKanren:
;; Techniques, Applications, and Implementations"
;; http://scholarworks.iu.edu/dspace/bitstream/handle/2022/8777/Byrd_indiana_0093A_10344.pdf?sequence=1
;; http://code.google.com/p/iucs-relational-research/

;; -----------------------------------------------------------------------------
;; Data Structures
;; (atom []) is cache, waiting streams are PersistentVectors

(defprotocol ISuspendedStream
  (ready? [this]))

(deftype SuspendedStream [cache ansv* f]
  ISuspendedStream
  (ready? [this]
          (not= @cache ansv*)))

(defn ^SuspendedStream make-ss [cache ansv* f]
  {:pre [(instance? clojure.lang.Atom cache)
         (list? ansv*)
         (fn? f)]}
  (SuspendedStream. cache ansv* f))

(defn ss? [x]
  (instance? SuspendedStream x))

(defn to-w [s]
  (into [] s))

(defn w? [x]
  (vector? x))

(defn w-check [w sk fk]
  (loop [w w a []]
    (cond
     (nil? w) (fk)
     (ready? (first w)) (sk
                         (fn []
                           (let [^SuspendedStream ss (first w)
                                 f (.f ss)
                                 w (to-w (concat a (next w)))]
                             (if (empty? w)
                               (f)
                               (mplus (f) (fn [] w))))))
     :else (recur (next w) (conj a (first w))))))

;; -----------------------------------------------------------------------------
;; Extend Substitutions to support tabling

(defprotocol ITabled
  (-reify-tabled [this v])
  (reify-tabled [this v])
  (alpha-equiv? [this x y])
  (reuse [this argv cache start end])
  (subunify [this arg ans]))

;; CONSIDER: subunify, reify-term-tabled, extending all the necessary types to
;; them

(extend-type Substitutions
  ITabled

  (-reify-tabled [this v]
                 (let [v (walk this v)]
                   (cond
                    (lvar? v) (ext-no-check this v (lvar (count (.s this))))
                    (coll? v) (-reify-tabled
                               (-reify-tabled this (first v))
                               (next v))
                    :else this)))

  (reify-tabled [this v]
                (let [v (walk* this v)]
                  (walk* (-reify-tabled empty-s v) v)))

  (alpha-equiv? [this x y]
                (= (-reify this x) (-reify this y)))

  (reuse [this argv cache start end]
         (let [start (or start @cache)
               end   (or end [])]
           (letfn [(reuse-loop [ansv*]
                     (if (= ansv* end)
                       [(make-ss cache start
                                 (fn [] (reuse this argv cache @cache start)))]
                       (Choice. (subunify this argv
                                          (reify-tabled this (first ansv*)))
                                (fn [] (reuse-loop (rest ansv*))))))]
             (reuse-loop start))))

  (subunify [this arg ans]
            (let [arg (walk this arg)]
              (cond
               (= arg ans) this
               (lvar? arg) (ext-no-check this arg ans)
               (coll? arg) (subunify
                            (subunify this (next arg) (next ans))
                            (first arg) (first ans))
               :else this))))

;; -----------------------------------------------------------------------------
;; Waiting Stream

(extend-type clojure.lang.IPersistentVector
  IBind
  (bind [this g]
        (w-check this
                 (fn [f] (bind f g))
                 (fn [] (to-w
                         (map (fn [^SuspendedStream ss]
                                (make-ss (.cache ss) (.ansv* ss)
                                         (fn [] (bind ((.f ss)) g))))
                              this)))))
  IMPlus
  (mplus [this f]
         (w-check this
                  (fn [fp] (mplus fp f))
                  (fn []
                    (let [a-inf (f)]
                      (if (w? a-inf)
                        (to-w (concat a-inf this))
                        (mplus a-inf (fn [] this)))))))
  ITake
  (take* [this]
         (w-check this
                  (fn [f] (take* f))
                  (fn [] ()))))

(defn master [argv cache]
  (fn [a]
    (when (every? (fn [ansv]
                    (not (alpha-equiv? a argv ansv)))
                  @cache)
      (do (swap! cache conj (reify-tabled a argv))
          a))))

;; -----------------------------------------------------------------------------
;; Syntax

;; TODO: consider the concurrency implications much more closely

(defn table
  "Function to table a goal. Useful when tabling should only persist
  for the duration of a run."
  [goal]
  (let [table (atom {})]
    (fn [& args]
      (let [argv args]
        (fn [a]
          (let [key (-reify a argv)
                cache (get @table key)]
            (if (nil? cache)
              (let [cache (atom ())]
                (swap! table assoc key cache)
                ((fresh []
                   (apply goal args)
                   (master argv cache)) a))
              (reuse a argv cache nil nil))))))))

(defmacro tabled
  "Macro for defining a tabled goal. Prefer ^:tabled with the 
  defne/a/u forms over using this directly."
  [args & grest]
  `(let [table# (atom {})]
     (fn [~@args]
       (let [argv# ~args]
         (fn [a#]
           (let [key# (-reify a# argv#)
                 cache# (get @table# key#)]
             (if (nil? cache#)
               (let [cache# (atom ())]
                 (swap! table# assoc key# cache#)
                 ((fresh []
                    ~@grest
                    (master argv# cache#)) a#))
               (reuse a# argv# cache# nil nil))))))))

;; =============================================================================
;; cKanren

;; See - Claire Alvis, Dan Friedman, Will Byrd, et al
;; "cKanren - miniKanren with Constraints"
;; http://www.schemeworkshop.org/2011/papers/Alvis2011.pdf
;; http://github.com/calvis/cKanren

(defn reifiable-c? [c]
  (satisfies? IReifiableConstraint c))

(defn enforceable-c? [c]
  (satisfies? IEnforceableConstraint c))

(defn ext-cs [cs oc]
  (if (-> oc meta :id)
    (updatec cs oc)
    (addc cs oc)))

(defn ^Substitutions update-cs [oc]
  (fn [^Substitutions a]
    (make-s (.s a) (.l a) (ext-cs (.cs a) oc))))

(defn domain-compare [a b]
  (if (and (number? a) (number? b))
    (compare a b)
    1))

(defmacro build-oc [op & args]
  `(makec clpfd (~op ~@args) '~(symbol op) [~@args]))

;; NOTE: can we avoid the intersection call here?

(defn update-var-dom [x dom]
  (fn [a]
    (let [xdom (walk a x)]
      (if (domain? xdom)
        (let [new-dom (intersection xdom dom)]
          (when new-dom
            (ext-no-check a x new-dom)))
        (ext-no-check a x dom)))))

(defn process-dom [v dom]
  (fn [a]
    (cond
     (lvar? v) ((update-var-dom v dom) a)
     (member? dom v) a
     :else nil)))

(defn map-sum [f]
  (fn loop [ls]
    (if (empty? ls)
      (fn [a] nil)
      (conde
        [(f (first ls))]
        [(loop (rest ls))]))))

(defn updateg [u v]
  (fn [a]
    (update a u v)))

(declare force-ans)

(extend-protocol IForceAnswerTerm
  nil
  (-force-ans [u] s#)

  Object
  (-force-ans [u] s#)

  clojure.lang.Sequential
  (-force-ans [u]
    (all
     (force-ans (first u))
     (force-ans (rest u))))

  ;; clojure.lang.IPersistentMap
  ;; clojure.lang.IPersistentSet
  )

(defn force-ans [u]
  (fn [a]
    (let [v (walk a u)]
      (if (domain? v)
        ((map-sum (fn [v] (updateg u v))) (expand v))
        (-force-ans v)))))

(defn composeg [g0 g1]
  (fn [a]
    (let [a (g0 a)]
      (and a
           (g1 a)))))

(defn run-constraint [c]
  (fn [^Substitutions a]
    (let [cs (.cs a)]
      (if (containsc? cs c)
        ((proc c) (make-s (.s a) (.l a) cs))
        a))))

(defn run-constraints [x xcs]
  (if xcs
    (composeg
     (run-constraint (first xcs))
     (run-constraints x (next xcs)))
    s#))

(defn run-constraints* [xs cs]
  (if (or (zero? (count cs))
          (nil? (seq xs)))
    s#
    (let [x (first xs)
          xcs (get cs x)]
      (if (seq xcs)
        (composeg
         (run-constraints x xcs)
         (run-constraints* (next xs) cs))
        (run-constraints* (next xs) cs))) ))

(defn verify-all-bound [a constrained]
  (when (seq constrained)
    (let [f (first constrained)]
      (if (and (lvar? f) (= f (walk a f)))
        (throw (Exception. (str "Constrained variable " f " without domain")))
        (recur a (rest constrained))))))

(defn enforce-constraints [x]
  (all
    (force-ans x)
    (fn [^Substitutions a]
      (let [^ConstraintStore cs (.cs a)
            constrained (keys (.km cs))]
        (verify-all-bound a constrained)
        ((onceo (force-ans constrained)) a)))))

(defn reify-constraints [v r]
  (fn [^Substitutions a]
    (let [^ConstraintStore cs (.cs a)
          rcs (apply concat
                     (-> (get cs v)
                         (filter reifiable-c?)
                         (map (fn [oc]
                                ((reifyc oc v r) a)))))]
      (if (empty? rcs)
        (choice v empty-f)
        (choice `(~v :- ~@rcs) empty-f)))))

(defn update-prefix [^Substitutions a ^Substitutions ap]
  (let [l (.l a)]
    ((fn loop [lp]
       (if (identical? l lp)
         s#
         (let [[lhs rhs] (first lp)]
          (composeg
           (updateg lhs rhs)
           (loop (rest lp)))))) (.l ap))))

(defn ==-c [u v]
  (fn [^Substitutions a]
    (when-let [ap (unify a u v)]
      (if (pos? (count (.cs a)))
        ((update-prefix a ap) a)
        ap))))

(defn reifyg [x]
  (fn [^Substitutions a]
    (let [v (walk* a x)
          r (-reify empty-s v)]
      (if (empty? r)
        (list v)
        (let [v (walk* r v)]
          (reify-constraints a r v))))))

(defprotocol IMakeDomain
  (make-dom [this xs]))

(defprotocol IMakeConstraint
  (makec [this proc rator rands]))

;; =============================================================================
;; CLP(FD)

;; NOTE: aliasing FD? for solving problems like zebra - David

(deftype FDConstraint [proc rator rands meta]
  clojure.lang.IObj
  (meta [this]
    meta)
  (withMeta [this new-meta]
    (FDConstraint. proc rator rands meta))
  IEnforceableConstraint
  IConstraint
  (proc [_] proc)
  (rator [_] rator)
  (rands [_] rands))

(deftype CLPFD []
  IMakeDomain
  (make-dom [this xs]
    (apply sorted-set xs))
  IMakeConstraint
  (makec [this proc rator rands]
    (FDConstraint. proc rator rands nil)))

(def clpfd (CLPFD.))

(defmacro extend-to-fd [t]
  `(extend-type ~t
     IDomain
     IFiniteDomain
     (~'lb [this#] this#)
     (~'ub [this#] this#)
     (~'bounds [this#] (pair this# this#))
     (~'member? [this# v#] (= this# v#))
     (~'expand [this#] (sorted-set this#))
     (~'drop-before [this# n#]
       (if (= this# n#)
         n#
         nil))
     (~'keep-before [this# n#]
       (if (= this# n#)
         n#
         nil))
     (~'disjoint? [this# that#]
       (if (number? that#)
         (not= this# that#)
         (disjoint? (expand this#) (expand that#))))
     (~'intersection [this# that#]
       (if (number? that#)
         (when (= this# that#)
           this#)
         (intersection that# this#)))
     (~'difference [this# that#]
       (if (number? that#)
         (when (= this# that#)
           this#)
         (intersection that# this#)))))

(extend-to-fd java.lang.Byte)
(extend-to-fd java.lang.Short)
(extend-to-fd java.lang.Integer)
(extend-to-fd java.lang.Long)
(extend-to-fd java.math.BigInteger)
(extend-to-fd clojure.lang.BigInt)

(deftype IntervalFD [_lb _ub]
  IDomain
  IFiniteDomain
  (lb [_] _lb)
  (ub [_] _ub)
  (bounds [_] (pair _lb _ub))
  (member? [this v]
    (and (>= v _lb) (<= v _ub)))
  (disjoint? [this that]
    (if (instance? IntervalFD that)
      (or (< (ub this) (lb that))
          (< (lb this) (ub that)))
      (disjoint? (expand this) (expand that))))
  (drop-before [this n]
    (cond
     (= n _ub) n
     (< n _lb) this
     (> n _ub) nil
     :else (IntervalFD. n _ub)))
  (keep-before [this n]
    (cond
     (= n _lb) n
     (> n _ub) this
     (< n _lb) nil
     :else (IntervalFD. _lb n)))
  (expand [this] (apply sorted-set (range _lb _ub)))
  (super? [this that]
    (and (<= _lb (lb that))
         (>= _ub (ub that))))
  (intersection [this that]
    (cond
     (instance? IntervalFD that)
     (let [^IntervalFD that that
           lb (max _lb (.lb that))
           ub (min _ub (.ub that))]
        (cond
         (= lb ub) lb
         (< lb ub) (IntervalFD. lb ub)
         :else nil))
     (instance? clojure.lang.PersistentTreeSet that)
     (let [s (intersection that this)]
       (when-not (empty? s)
         s))
     (number? that) (when (and (>= that _lb) (<= that _ub))
                      that)
     :else (let [s (set/intersection (expand this) (expand that))]
             (when-not (empty? s)
               s))))
  (difference [this that]
    )
  IDisequalityConstrain
  (!=c [this that]))

(defn ^IntervalFD interval
  ([ub] (IntervalFD. 0 ub))
  ([lb ub] (IntervalFD. lb ub)))

(extend-type clojure.lang.PersistentTreeSet
  IDomain
  IFiniteDomain
  (lb [this]
    (first this))
  (ub [this]
    (first (rseq this)))
  (member? [this v]
    (contains? this v))
  (disjoint? [this that]
    (empty? (set/intersection this (expand that))))
  (drop-before [this n]
    (apply sorted-set (drop-while #(< % n)) this))
  (keep-before [this n]
    (apply sorted-set (take-while #(< % n)) this))
  (expand [this] this)
  (intersection [this that]
    (cond
     (number? that)
     (when (member? this that)
       that)
     (super? that this) this
     :else (let [s (into (sorted-set)
                     (filter #(member? that %) this))]
             (when-not (empty? s)
               s))))
  (difference [this that]
    (let [s (into (sorted-set)
              (filter #(not (member? that %)) this))]
      (when-not (empty? s)
        s))))

(defmacro infd [& xs-and-dom]
  (let [xs (butlast xs-and-dom)
        dom (last xs-and-dom)]
    `(let [dom# ~dom]
      (fresh []
        ~@(map (fn [x]
                 `(domfd ~x dom#))
               xs)))))

(defn domfd [x dom]
  (fn [a]
    ((process-dom (walk a x) dom) a)))

(defn walk-var [a [v b]]
  `(~b (walk ~a ~v)))

(defmacro let-dom [a vars & body]
  `(let [~@(mapcat (partial walk-var a) (partition 2 vars))]
     ~@body))

(defmacro c-op [op vars & body]
  (let [ps (partition 2 vars)
        vs (map first ps)
        ds (map second ps)]
    `(fn [a#]
       (let-dom a# ~vars
         (let [oc# (build-oc ~op ~@vs)]
           (if (and ~@(map (fn [d] `(domain? ~d)) ds))
             ((composeg (update-cs oc#) ~@body) a#)
             ((update-cs oc#) a#)))))))

(defn exclude-from [dom1 a xs]
  (loop [xs xs gs []]
    (if (empty? xs)
      (reduce composeg gs)
      (let [x (first xs)
            dom2 (walk a x)]
        (if (domain? dom2)
          (recur (rest xs) (conj gs (process-dom x (difference dom2 dom1))))
          (recur (rest xs) gs))))))

(defn !=fd-c [u v]
  (fn [^Substitutions a]
    (let [s (.s a)
          du (walk s u)
          dv (walk s v)]
      (cond
       (or (not (domain? du))
           (not (domain? dv))) ((update-cs (build-oc !=fd-c u v)) a)
       (= u v) false
       (disjoint? u v) a
       :else (let [oc (build-oc !=fd-c u v)]
               ((update-cs oc) a))))))

(defn =fd-c [u v]
  (c-op =c [u ud v vd]
    (let [i (intersection ud vd)]
      (composeg
       (process-dom u i)
       (process-dom v i)))))

(defn <=fd-c [u v]
  (c-op <=c [u ud v vd]
    (let [[ulb uub] (bounds ud)
          [vlb vub] (bounds vd)]
      (composeg
        (process-dom u (keep-before ud vub))
        (process-dom v (drop-before vd ulb))))))

(defn +fd-c [u v w]
  (c-op +c [u ud v vd w wd]
    (let [[wlb wub] (bounds wd)
          [ulb uub] (bounds ud)
          [vlb vub] (bounds vd)]
      (composeg
        (process-dom w (interval (+ ulb vlb) (+ uub vub)))
        (composeg
          (process-dom u (interval (- wlb vub) (- wub vlb)))
          (process-dom v (interval (- wlb uub) (- wub ulb))))))))

(defn all-difffd-c* [ys ns]
  (fn [a]
    (let [ns (make-dom clpfd ns)]
     (loop [ys ys ns ns xs ()]
       (if (empty? ys)
         (let [oc (build-oc all-difffd-c* xs ns)]
           ((composeg
             (update-cs oc)
             (exclude-from ns a xs))
            a))
         (let [y (walk a (first ys))]
           (cond
            (lvar? y) (recur (rest ys) ns (cons y xs))
            (member? y ns) nil
            :else (recur (rest ys) (conj ns y) xs))))))))

(defn all-difffd-c [v*]
  (fn [a]
    (let [v* (walk a v*)]
      (cond
       (lvar? v*) (let [oc (build-oc all-difffd-c v*)]
                   ((update-cs oc) a))
       :else (let [{x* true n* false} (group-by var? v*)]
               ((all-difffd-c* x* n*) a))))))

;; =============================================================================
;; CLP(Tree)

(deftype TreeConstraint [proc rator rands meta]
  clojure.lang.IObj
  (meta [this]
    meta)
  (withMeta [this new-meta]
    (TreeConstraint. proc rator rands meta))
  IReifiableConstraint
  IConstraint
  (proc [_] proc)
  (rator [_] rator)
  (rands [_] rands))

(deftype CLPTree []
  IMakeConstraint
  (makec [this proc rator rands]
    (TreeConstraint. proc rator rands nil)))

(def clptree (CLPTree.))

(defn recover-vars [p]
  (if (empty? p)
    #{}
    (let [f (first p)
          x (lhs f)
          v (rhs f)
          r (recover-vars (rest p))]
      (if (lvar? v)
        (conj r x v)
        (conj r x)))))

(defn oc->prefix [oc]
  (first (rands oc)))

;; TODO: unify should return the prefix sub, then can eliminate l - David

(defn subsumes? [s p pp]
  (when-let [sp (unify s p pp)]
    (identical? s sp)))

(declare !=neq-c)

(defn normalize-store [p]
  (fn [^Substitutions a]
    (loop [c (.c a) cp ()]
      (if (empty? c)
        (let [cp (ext-cs (build-oc !=neq-c p) cp)]
          (make-s (.s a) (.l a) cp))
        (let [oc (first c)]
          (if (= (rator oc) '!=new-c)
            (let [pp (oc->prefix oc)]
              (cond
               (subsumes? a pp p) a
               (subsumes? a p pp) (recur (rest c) cp)
               :else (recur (rest c) (cons oc cp))))
            (recur (rest c) (cons oc cp))))))))

;; TODO: change to lazy-seq
(defn prefix [s <s]
  (if (= s <s)
    ()
    (cons (first s) (prefix (rest s) <s))))

(defn !=neq-c [u v]
  (fn [a]
    (if-let [ap (unify a u v)]
      (let [p (prefix a ap)]
        (when (not (empty? p))
          ((normalize-store p) a)))
      a)))

(defn all-diffo [l]
  (conde
    [(== l ())]
    [(fresh (a) (== l [a]))]
    [(fresh (a ad dd)
      (== l (llist a ad dd))
      (!=c a ad)
      (all-diffo (llist a dd))
      (all-diffo (llist ad dd)))]))

(comment
  (run* [q]
    (== q 1))

  (dotimes [_ 10]
    (time
     (dotimes [_ 1e6]
       (run* [q]
         (== q 1)))))

  (let [x 50
        d (IntervalFD. 1 100)]
    (intersection d x))
  
  ;; ~20ms
  (let [x 50
        d (IntervalFD. 1 100)]
    (dotimes [_ 10]
      (time
       (dotimes [_ 1e6] 
         (intersection d x)))))

  (let [s (sorted-set 1 2 3)
        d (IntervalFD. 1 100)]
    (intersection d s))

  ;; 210ms
  (let [s (sorted-set 1 2 3)
        d (IntervalFD. 1 100)]
    (dotimes [_ 10]
      (time
       (dotimes [_ 1e6] 
         (intersection s d)))))

  ;; 60-80ms
  (let [n 1
        s (sorted-set 1 2 3)]
    (dotimes [_ 10]
      (time
       (dotimes [_ 1e6] 
         (intersection s n)))))
  )