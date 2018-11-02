(ns lwhorton.daden-http.specs
  (:require
    [cljs.spec.alpha :as s]
    )
  )

(s/def ::uri string?)
(s/def ::success fn?)
(s/def ::error fn?)
(s/def ::failure fn?)
(s/def ::cancellable? boolean?)
(s/def ::timeout pos?)
(s/def ::request-format #{:json :graphql})
(s/def ::response-format #{:json :detect})
(s/def ::request fn?)
(s/def ::response fn?)
(s/def ::name string?)
(s/def ::interceptor (s/keys :opt-un [::name
                                      ::request
                                      ::response]))
(s/def ::interceptors (s/coll-of ::interceptor))

(s/def ::query (s/and string? #(not (empty? %))))
(s/def ::operation-name (s/and string? #(not (empty? %))))
(s/def ::variables (s/nilable map?))
(s/def ::gql (s/keys :req-un [
                              ::query
                              ::operation-name
                              ]
                     :opt-un [
                              ::variables
                              ]))
(s/def ::graphql (s/keys :req-un [
                                  ::uri
                                  ::gql
                                  ::request-format
                                  ::response-format
                                  ]
                         :opt-un [
                                  ::success
                                  ::error
                                  ::failure
                                  ::cancellable?
                                  ::timeout
                                  ::interceptors
                                  ]))
(s/def ::graphql-dsl (s/or :single ::graphql
                           :multiple (s/coll-of ::graphql
                                                :kind vector?
                                                :min-count 1)))

(s/def ::method #{:get
                  :head
                  :post
                  :put
                  :delete
                  :connect
                  :options
                  :trace
                  :patch})
(s/def ::path string?)
(s/def ::param (s/or :str string?
                     :kw keyword?))
(s/def ::params (s/map-of ::param any?))
(s/def ::http (s/keys :req-un [
                               ::uri
                               ::method
                               ::request-format
                               ::response-format
                               ]
                      :opt-un [
                               ::path
                               ::success
                               ::error
                               ::failure
                               ::params
                               ::cancellable?
                               ::timeout
                               ::interceptors
                               ]))
(s/def ::http-dsl (s/or :single ::http
                        :multiple (s/coll-of ::http
                                             :kind vector?
                                             :min-count 1)))

(s/def ::dsl (s/or :http ::http
                   :graphql ::graphql))
