(ns lwhorton.daden-http.core
  (:require
    [ajax.core :as http]
    [ajax.interceptors :as i]
    [camel-snake-kebab.core :as k]
    [cljs.spec.alpha :as s]
    [clojure.walk :refer [postwalk]]
    [goog.json :as json]

    [lwhorton.daden-http.specs :as sp]
    ))

(defn- transform-keys
  "Recursively transform all keys in map m with transform function t."
  [t m]
  (let [f (fn [[k v]] [(t k v) v])]
    (postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(defn- edn->snake_case_json
  "Write the given edn into json after converting kebab-case into snake_case"
  [edn]
  (->> edn
       (transform-keys k/->snake_case_keyword)
       (clj->js)
       (.serialize (goog.json.Serializer.))))

(defn- edn->camelCaseJson
  "Write the given edn to json after converting kebab-case into camelCase."
  [edn]
  (->> edn
       (transform-keys k/->camelCaseString)
       (clj->js)
       (.serialize (goog.json.Serializer.))))

(defn- json->edn
  "Write the given json to kebabk-case edn."
  [jsn]
  (->> jsn
       (json/parse)
       (js->clj)
       (transform-keys k/->kebab-case-keyword)))

(defn- get-xhr-headers [^js xhr]
  (transform-keys k/->kebab-case-keyword (js->clj (.getResponseHeaders xhr))))

(defn- get-content-type [headers]
  (clojure.string/split (:content-type headers) #";"))

(defn- xhr->edn [^js xhr]
  {:status (.getStatus xhr)
   :status-text (.getStatusText xhr)
   :uri (.getLastUri xhr)
   :headers (get-xhr-headers xhr)
   :body (-> xhr
             (.getResponseText)
             (json->edn))})

(defmulti request-fmt identity)

(defmethod request-fmt :json [_]
  {:write edn->snake_case_json
   :content-type "application/json"})

(defmethod request-fmt :graphql [_]
  {:write edn->camelCaseJson
   :content-type "application/json"})

(defmulti response-fmt identity)

(defmethod response-fmt :json [_]
  {:read xhr->edn
   :content-type "application/json"})

;; many systems return an empty json response when returning nil, but the spec
;; states they should return null. handle this empty-json-body case, as well as
;; cases where a detected content-type is not supported.
(defmethod response-fmt nil [_]
  {:read (fn [_] nil)})

(def content-type->fmt {"application/json" :json})

(defn- detect-response-fmt-read [opts]
  (fn [xhr]
    (let [headers (get-xhr-headers xhr)
          [content-type _] (get-content-type headers)]
      ((:read (response-fmt (get content-type->fmt content-type))) xhr))))

(defmethod response-fmt :detect [opts]
  {:read (detect-response-fmt-read opts)
   ;; @TODO this default will work as the accept header until we have reason to
   ;; override it by varied / custom media types on a per-request basis
   :content-type "application/json"})

(defprotocol Responsible
  "Unifies over-the-wire operations, which can either succeed, error, or fail.
  Given an xhrio response object (containing things like status, body, headers,
  etc.), return true or false from each status fn to determine the terminal
  state of the response."
  (-success? [t resp])
  (-error? [t resp])
  (-failure? [t resp]))

(deftype
  ^{:doc
    "Provides the default ApolloGraphql semantics for success, error, failure.

    :success - a response returned from the graphql server and no :errors key
    was present on the response body.

    :error - a response returned with a non-empty :errors key on the body.

    :failure - either a response was not returned (due to network error,
    timeout, etc) or the server returned with a 5xx status code."}
  ApolloGraphql []
  Responsible
  (-success? [_ {:keys [body]}]
    (not (contains? body :errors)))

  (-error? [_ {:keys [body]}]
    (and (contains? body :errors)
         (not-empty (:errors body))))

  (-failure? [_ {:keys [status]}]
    (or (> status 499)
        (= 0 status)
        (= -1 status))))

(deftype
  ^{:doc
    "Provides the default HTTP semantics for success, error, failure.

    :success - a response returned with a status of 2xx.

    :error - a response returned with a status of 4xx.

    :failure - either a response was not returned (due to network error,
    timeout, etc), or a response returned with a status of 4xx."}
  HTTP []
  Responsible
  (-success? [_ {:keys [status]}]
    (< status 400))

  (-error? [_ {:keys [status]}]
    (> 500 status 399))

  (-failure? [_ {:keys [status]}]
    (or (> status 499)
        (= 0 status)
        (= -1 status))))

(defn- add-handler [{:keys [success error failure always] :as dsl} type]
  (letfn [(handler [[ok? result]]
            ((or always (constantly nil)) [ok? result])

            (cond
              (-failure? type result)
              (when-not (= :aborted (:failure result))
                (failure result))

              (-error? type result)
              (error result)

              (-success? type result)
              (success result)

              :else
              (println "We somehow managed no failure, no error, and no success on this response:" result)))]
    (assoc dsl :handler handler)))

(def in-flight (atom {}))

(defn- serialize-for-eq
  "If every key provided in this list is identical between two dsls, the dsls
  are identical. We require this because identical anonymous functions are not
  equal, and a lot of our dsl consists of functions."
  [dsl]
  (select-keys dsl [:uri
                    :method
                    :cancellable
                    :timeout
                    :request-format
                    :response-format
                    :interceptors]))

(defn- maybe-cancel-in-flight [dsl]
  (if (:cancellable? dsl)
    (let [^js xhrio (get @in-flight (serialize-for-eq dsl))]
      (when (and xhrio (true? (.isActive xhrio)))
        (.abort xhrio))
      dsl)
    dsl))

(defn- release-on-return [dsl]
  (if (:cancellable? dsl)
    (assoc dsl :always (fn [_] (swap! in-flight dissoc (serialize-for-eq dsl))))
    dsl))

(defn- capture-in-flight [dsl ^js xhrio]
  (if (:cancellable? dsl)
    (swap! in-flight assoc (serialize-for-eq dsl) xhrio)
    xhrio))

(defn- merge-defaults [{:keys [
                               path
                               response-format
                               request-format
                               ]
                        :or {path ""
                             response-format :detect}
                        :as dsl}]
  (merge dsl
         {:path path
          :timeout (or (:timeout dsl) (* 30 1000))
          :request-format (request-fmt request-format)
          :response-format (response-fmt response-format)}))

(defn- to-ajax-map
  "Convert between our DSL and the clj-ajax config map."
  [dsl]
  (-> dsl
      (update :uri str (:path dsl))
      (update :interceptors (fn [is] (map i/to-interceptor is)))
      (clojure.set/rename-keys {:request-format :format})))

(defn- make-request [cfg]
  (http/ajax-request cfg))

(defn- do-request
  [type dsl]
  (let [xhrio (-> dsl
                  (maybe-cancel-in-flight)
                  (release-on-return)
                  (add-handler type)
                  (merge-defaults)
                  (to-ajax-map)
                  (make-request))]
    (capture-in-flight dsl xhrio)))

(defn request [type' dsl]
  (s/assert ::sp/dsl dsl)
  (cond
    (sequential? dsl) (doseq [d dsl] (do-request type' d))
    (map? dsl) (do-request type' dsl)))

(def
  ^{:doc
    "
    A DSL for specifying an http event.

    :uri - string uri of the root api (e.g. https://my.domain.com/api)

    :success - callback fn invoked on success

    :error - callback fn invoked on error

    :failure - callback fn invoked on failure

    :path - a string path to append to :uri, intended to point to the resource
    you wish to :method upon (e.g. :path '/posts/1'). mostly a convenience.

    :method - keyword version the http method to invoke (e.g. :get, :post,
    :delete, :options)

    :request-format - one of :json or :graphql. sets the content-type header on
    the request and serializes outgoing request parameters accordingly.

    :response-format - one of :json or :detect. sets the accept header on the
    request and parses the incoming request body accordingly.

    :cancellable? - when set to true, any calls to request that are exactly
    identical to an already in-flight request will cause the previous request to
    be aborted, and the new request to run instead, ad-infinitum. equality is
    compared against the entire dsl.

    :params - a map of parameters to be serialized into the request. see
    :request-format for serialization options.

    :timeout - time (ms) to wait for a response before aborting the request
    (and invoking the :failiure handler). defaults to 30000ms.

    :interceptors - an array of interceptors to use for this request. order
    matters. (e.g. {:request (fn [req]) :response (fn [res]) :name
    'my-interceptor})

    See the :lwhorton.daden-http.specs for a full description of the DSL.
    "}
  http
  ::sp/http-dsl)

(def
  ^{:doc
    "
    Provide a DSL for specifying a graphql-over-http event.

    Differs slightly from the http DSL.

    :gql - a map consisting of:
        :query - the graphql query/mutation, as a string
        :operation-name - the name of the query/mutation, as a string.
        :variables - a map of variables to template into the query string.
        (e.g. {:query 'query MyQuery($color: String!) { teams { jersey($color) } }'
               :operation-name 'MyQuery'
               :variables {:color 'blue'}})

    See the :lwhorton.daden-http.specs for a full description of the DSL.
    "}
  graphql
  ::sp/graphql-dsl)
