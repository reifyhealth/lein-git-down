(ns lein-git-down.impl.pom
  (:require [clojure.string :as string]
            [clojure.walk :as walk])
  (:import (java.io File)
           (java.util Iterator)
           (javax.xml.parsers DocumentBuilderFactory)
           (javax.xml.transform Transformer TransformerFactory OutputKeys)
           (javax.xml.transform.dom DOMSource)
           (javax.xml.transform.stream StreamResult)
           (org.w3c.dom Document Element Node NodeList)
           (org.w3c.dom.traversal DocumentTraversal NodeFilter)))

(def ^:private xml-schema-ns
  "http://www.w3.org/2001/XMLSchema-instance")

(def ^:private xml-pom-ns
  "http://maven.apache.org/POM/4.0.0")

(def ^:private xml-pom-schema
  "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd")

(defn- write-pom!
  [^Document pom ^File file]
  (let [^Transformer t
        (doto (.newTransformer (TransformerFactory/newInstance))
          (.setOutputProperty OutputKeys/ENCODING "UTF-8")
          (.setOutputProperty OutputKeys/INDENT "yes")
          (.setOutputProperty "{http://xml.apache.org/xslt}indent-amount" "2"))
        ds (DOMSource. pom)]
    (.transform t ds (StreamResult. file))))

(defn- text-node
  [^Document doc ^Element parent k v]
  (.appendChild parent
    (doto (.createElement doc k)
      (.appendChild (.createTextNode doc v)))))

(defn- branch-node
  [^Document doc ^Element parent k]
  (.appendChild parent (.createElement doc k)))

(defn- build-node
  [^Document pom ^Element project source-path resource-paths]
  (when (or source-path (not-empty resource-paths))
    (let [build (branch-node pom project "build")]
      (when source-path
        (text-node pom build "sourceDirectory" source-path))
      (when (not-empty resource-paths)
        (let [resources (branch-node pom build "resources")]
          (doseq [rd resource-paths]
            (let [resource (branch-node pom resources "resource")]
              (text-node pom resource "directory" rd))))))))

(defn- dependencies-node
  [^Document pom ^Element project dependencies]
  (when (not-empty dependencies)
    (let [deps (branch-node pom project "dependencies")]
      (doseq [{:keys [group artifact version classifier exclusions]} dependencies]
        (when (and group artifact version)
          (let [dep (branch-node pom deps "dependency")]
            (text-node pom dep "groupId" group)
            (text-node pom dep "artifactId" artifact)
            (text-node pom dep "version" version)
            (when classifier
              (text-node pom dep "classifier" classifier))
            (when (not-empty exclusions)
              (let [excls (branch-node pom dep "exclusions")]
                (doseq [{:keys [group artifact]} exclusions]
                  (when (and group artifact)
                    (let [excl (branch-node pom excls "exclusion")]
                      (text-node pom excl "group" group)
                      (text-node pom excl "artifactId" artifact))))))))))))

(defn gen-pom
  [{:keys [group artifact version source-path resource-paths dependencies]}
   ^File destination]
  (let [^Document pom
        (.. (doto (DocumentBuilderFactory/newInstance) (.setNamespaceAware true))
            newDocumentBuilder
            newDocument)
        ^Element project
        (doto (.createElement pom "project")
          (.setAttribute "xmlns:c" xml-pom-ns)
          (.setAttribute "xmlns:xsi" xml-schema-ns)
          (.setAttribute "xsi:schemaLocation" xml-pom-schema))]
    (.appendChild pom project)
    (text-node pom project "modelVersion" "4.0.0")
    (text-node pom project "groupId" group)
    (text-node pom project "artifactId" artifact)
    (text-node pom project "version" version)
    (text-node pom project "name" artifact)
    (build-node pom project source-path resource-paths)
    (dependencies-node pom project dependencies)
    (write-pom! pom destination))
  destination)

(defn- node-iterator-seq
  [traversal]
  (let [ni (.createNodeIterator traversal
             (.getDocumentElement traversal)
             (bit-or NodeFilter/SHOW_TEXT NodeFilter/SHOW_ELEMENT)
             nil
             true)]
    (iterator-seq
      (proxy [Iterator] []
        (hasNext []
          (boolean
            (when (.nextNode ni)
              (.previousNode ni))))
        (next []
          (.nextNode ni))))))

(defrecord ArrayNode [^Node node index]
  Node
  (getParentNode [_] (.getParentNode node))
  (getNodeName [_] (.getNodeName node))
  (getNodeType [_] (.getNodeType node))
  (getNodeValue [_] (.getNodeValue node)))

(defn- nodes-equal?
  [^Node x ^Node y]
  (= (if (instance? ArrayNode x) (:node x) x)
     (if (instance? ArrayNode y) (:node y) y)))

(defn- node-list-seq
  ([^NodeList ns]
    (node-list-seq ns 0))
  ([^NodeList ns i]
    (when-let [n (.item ns i)]
      (lazy-seq (cons n (node-list-seq ns (inc i)))))))

(defn- equal-siblings?
  [^Node n]
  (->> (.getParentNode n)
       .getChildNodes
       node-list-seq
       (map #(.getNodeName %))
       (filter #(= (.getNodeName n) %))
       (apply distinct?)
       not))

(defn- conj-parents*
  [{:keys [parents] :as p} ^Node n]
  (if (equal-siblings? n)
    (let [k (str "/" (string/join "/" parents) (.getNodeName n))
          v (->ArrayNode n (inc (get-in p [:array-nodes k :index] -1)))]
      (-> p
          (update :parents conj v)
          (assoc-in [:array-nodes k] v)))
    (update p :parents conj n)))

(defn- update-parents
  [{:keys [parents] :as p} ^Node n]
  (cond (empty? parents)
        (assoc p :parents [n])

        (nodes-equal? (last parents) (.getParentNode n))
        (conj-parents* p n)

        :default
        (recur (update p :parents (comp vec butlast)) n)))

(defn- assoc-path
  [parents]
  (into []
        (comp
          (map
            (fn [n]
              (let [nn (-> n .getNodeName keyword)]
                (if (instance? ArrayNode n) [nn (:index n)] [nn]))))
          cat)
        parents))

(defn- flatten-arrays
  [pom]
  (walk/prewalk
    (fn [x]
      (if (and (map? x) (every? number? (keys x)))
        (mapv second (sort-by key x))
        x))
    pom))

(def ^:private +load-external-dtd-feature+
  "http://apache.org/xml/features/nonvalidating/load-external-dtd")

(defn parse-pom
  [^File pom-file]
  (let [traversal (-> (doto (DocumentBuilderFactory/newInstance)
                        (.setFeature +load-external-dtd-feature+ false))
                      .newDocumentBuilder
                      (.parse pom-file))]
    (-> (reduce
          (fn [p ^Node n]
            (condp == (.getNodeType n)
              Node/ELEMENT_NODE
              (update-parents p n)

              Node/TEXT_NODE
              (if-not (string/blank? (.getNodeValue n))
                (assoc-in p (assoc-path (:parents p)) (.getNodeValue n))
                p)

              p))
          {:parents [] :array-nodes {}}
          (node-iterator-seq traversal))
        :project
        flatten-arrays)))
