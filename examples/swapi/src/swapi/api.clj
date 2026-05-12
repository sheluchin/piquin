(ns swapi.api
  "Pathom3 connector for the SWAPI GraphQL endpoint."
  (:require
   [cheshire.core :as cjson]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.graphql :as p.gql]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [org.httpkit.client :as http]))

(defn- request-swapi-graphql
  [query]
  (-> @(http/request
        {:url     "https://swapi-graphql.netlify.app/graphql"
         :method  :post
         :headers {"Content-Type" "application/json"
                   "Accept"       "*/*"}
         :body    (cjson/generate-string {:query query})})
      :body
      (cjson/parse-string)))

(def env
  (-> {}
      (p.gql/connect-graphql
       {::p.gql/namespace "swapi"}
       request-swapi-graphql)))

(def ^:private index-io (::pci/index-io env))

(defn reachable-attributes
  "Returns a map of reachable attributes from the given input attributes."
  [input-attrs]
  (reduce (fn [acc attr]
            (let [reachable (get index-io #{attr})]
              (if reachable (merge acc reachable) acc)))
          {}
          input-attrs))

(defn run-query
  "Runs an EQL query against the SWAPI environment."
  [q]
  (p.eql/process env q))
