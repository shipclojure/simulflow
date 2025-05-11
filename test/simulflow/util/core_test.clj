(ns simulflow.util.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [simulflow.utils.core :refer [ends-with-sentence?]]))

(deftest ends-with-sentence?-test
  (testing "Basic sentence endings"
    (is (ends-with-sentence? "This is a sentence."))
    (is (ends-with-sentence? "Is this a question?"))
    (is (ends-with-sentence? "What an exclamation!"))
    (is (ends-with-sentence? "First part; second part"))
    (is (ends-with-sentence? "One thing: another thing")))

  (testing "Asian punctuation marks"
    (is (ends-with-sentence? "这是一个句子。"))
    (is (ends-with-sentence? "这是问题吗？"))
    (is (ends-with-sentence? "多么令人兴奋！"))
    (is (ends-with-sentence? "第一部分："))
    (is (ends-with-sentence? "第一个；")))

  (testing "Should not match abbreviations with periods"
    (is (not (ends-with-sentence? "U.S.A.")))
    (is (ends-with-sentence? "etc.")))

  (testing "Should not match after numbers"
    (is (not (ends-with-sentence? "1.")))
    (is (not (ends-with-sentence? "Chapter 2.")))
    (is (not (ends-with-sentence? "Section 3.2."))))

  (testing "Should not match time markers"
    (is (not (ends-with-sentence? "3 a.")))
    (is (not (ends-with-sentence? "11 p.")))
    (is (ends-with-sentence? "9 a.m."))
    (is (ends-with-sentence? "10 p.m.")))

  (testing "Should not match after titles"
    (is (not (ends-with-sentence? "Mr.")))
    (is (not (ends-with-sentence? "Mrs.")))
    (is (not (ends-with-sentence? "Ms.")))
    (is (not (ends-with-sentence? "Dr.")))
    (is (not (ends-with-sentence? "Prof."))))

  (testing "Complex sentences"
    (is (ends-with-sentence? "The U.S.A. is a country."))
    (is (ends-with-sentence? "She has a Ph.D. in biology."))
    (is (ends-with-sentence? "Mr. Smith went home.")))

  (testing "Edge cases"
    (is (not (ends-with-sentence? "")))
    (is (not (ends-with-sentence? "Mrs")))
    (is (ends-with-sentence? "Yes!")))

  (testing "Mixed punctuation"
    (is (ends-with-sentence? "Really?!"))
    (is (ends-with-sentence? "No way...!"))
    (is (ends-with-sentence? "First: then!")))

  (testing "Whitespace handling"
    (is (ends-with-sentence? "End of sentence.  "))
    (is (ends-with-sentence? "Question? "))
    (is (not (ends-with-sentence? "Incomplete "))))

  (testing "Numbers not followed by periods"
    (is (ends-with-sentence? "Score: 5"))))
