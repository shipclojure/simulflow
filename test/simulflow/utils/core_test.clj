(ns simulflow.utils.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [simulflow.utils.core :as u]))

(deftest content-type-test
  (testing "extracts Content-Type with proper casing"
    (is (= "application/json"
           (u/content-type {"Content-Type" "application/json"}))))
  (testing "extracts content-type with lowercase"
    (is (= "text/html"
           (u/content-type {"content-type" "text/html"}))))
  (testing "extracts :content-type as keyword"
    (is (= "application/xml"
           (u/content-type {:content-type "application/xml"}))))
  (testing "prioritizes Content-Type over content-type"
    (is (= "application/json"
           (u/content-type {"Content-Type" "application/json"
                            "content-type" "text/html"}))))
  (testing "prioritizes Content-Type over :content-type"
    (is (= "application/json"
           (u/content-type {"Content-Type" "application/json"
                            :content-type "text/html"}))))
  (testing "prioritizes content-type over :content-type"
    (is (= "text/html"
           (u/content-type {"content-type" "text/html"
                            :content-type "application/xml"}))))
  (testing "returns nil for empty headers"
    (is (nil? (u/content-type {}))))
  (testing "returns nil for nil headers"
    (is (nil? (u/content-type nil))))
  (testing "returns nil when no content-type variants found"
    (is (nil? (u/content-type {"Authorization" "Bearer token"})))))
