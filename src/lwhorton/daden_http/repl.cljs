(ns lwhorton.daden-http.repl)

(comment
  (def http-uri (atom ""))
  (def graphql-uri (atom ""))

  ;; yea we dont want this
  (defn init! [{:keys [http graphql]}]
    (reset! http-uri http)
    (reset! graphql-uri graphql))

  ;; consumer
  (daden/init! {:http-uri "https://my.domain/api"
                :graphql-uri "https://my.other.domain/graphql"})

  (def uri (atom "..."))

  (reg-fx :http (fn [dsl]
                  (daden/? (HTTP. @uri))
                  (daden/? (assoc dsl :uri @uri))
                  ;; OR
                  (daden/? (GraphQL. @uri))
                  (daden/? (assoc dsl :uri (System/getenv)))

                  (assoc dsl :success (fn [resp] (my-func resp)))
                  ))

  ;; must provide uri on each invocation attached to the dsl
  {:graphql {:uri ""}}
  {:http {:path "/foo/bar"}}
  {:http {:path "/foo/baz"}}

  (deftype Sendable []
    (-maybe-cancel []))

  (defprotocol Wireable
    "Unifies transforming a data DSL into executables that send data over a wire."
    (-send [_] "Invoke the wire protocol pipeline, invoking handler when finished."))

  (deftype HTTP [dsl]
    Wireable
    (-send [x]
      (-> dsl
          (cancel)
          (to-handler)
          (merge-overrides)
          (do-request)
          (capture-in-flight))))

  (deftype GraphQL [dsl]
    Wireable
    (-send [x]
      (-> dsl
          (cancel)
          (to-handler)
          (merge-overrides)
          (do-request)
          (capture-in-flight))))

  (defprotocol Foo
    (-a [_ x])
    (-b [_ x]))

  (deftype X [field]
    Foo
    (-a [_ x]
      (println field)
      [x])
    (-b [x]
      (println field)
      [x]))

  (conj (-a (X. 1) "hello"))

  (-b (X. 2))
  (-b [X])

  (def h1 {:path "/path"
           :method :get})

  (s/conform ::sp/dsl h1)
  (= ::s/invalid (s/conform ::sp/dsl {}))



{:http {:path "/entity"
        :request-format :csv}}

{:http {:path "/entity"
        :request-format :json}}

{:http {:path "/entity"
        :request-format :detect}}

(deftype MyGraphql []
  daden/Wire
  (-success? [_ {:keys [body]}]
    (not (contains? body :errors)))

  (-error? [_ {:keys [body]}]
    (and (contains? body :errors)
         (not-empty (:errors body))))

  (-failure? [_ {:keys [status]}]
    (or (> status 499)
        (= 0 status)
        (= -1 status))))
)
