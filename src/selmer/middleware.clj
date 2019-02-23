(ns selmer.middleware
  (:require [selmer.parser :as parser]))

(defn handle-template-parsing-error [ex]
  (let [{:keys [type error-template] :as data} (ex-data ex)]
    (if (= :selmer/validation-error type)
      {:status  500
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body    (parser/render error-template data)}
      (throw ex))))

(defn wrap-error-page
  "development middleware for rendering a friendly error page when a parsing error occurs"
  [handler]
  (fn
    ([request]
     (try
       (handler request)
       (catch clojure.lang.ExceptionInfo ex
         (handle-template-parsing-error ex))))
    ([request respond raise]
     (try
       (handler request respond raise)
       (catch clojure.lang.ExceptionInfo ex
         (respond (handle-template-parsing-error ex)))))))
