(ns guaranteed-delivery.core
  (:gen-class))

(def ^:dynamic  *error-prob* 1/60)

(defn make-pipe
  []
  (atom []))

(defn error-byte
  "Creates a byte with *error-prob* random 1s"
  []
  (loop [i 0
         byte 0]
    (cond
      (= i 8)
      byte
      (zero? (rand-int (denominator *error-prob*)))
      (recur (inc i) (bit-or byte (bit-shift-left 1 i)))
      :default
      (recur (inc i) byte))))

(defn bit-error
  "Randomly flip bits at *error-prob*"
  [byte-arr]
  (mapv #(bit-xor %1 (error-byte)) byte-arr))

(defn str->byte-vec
  [s]
  (mapv int s))

(defn byte-vec->str
  [bv]
  (reduce #(str %1 (char %2)) "" bv))

(defn sendpkt
  [pipe pkt]
  (swap! pipe conj (bit-error pkt)))

(defn recvpkt
  [pipe]
  ;; Dirty spin-lock. Must do something about this later.
  (while (empty? @pipe))
  (let [out (peek @pipe)]
    (swap! pipe pop)
    out))

(defprotocol Sender
  (send-msgs [_ msgs]))

(defprotocol Receiver
  (recv-msgs [_ n-msgs]))

(defrecord NaiveSender [in-pipe out-pipe]
  Sender
  (send-msgs [_ msgs]
    (doseq [m msgs]
      (do
        (sendpkt out-pipe (str->byte-vec m))
        (recvpkt in-pipe)))))

(defrecord NaiveReceiver [in-pipe out-pipe]
  Receiver
  (recv-msgs [_ n-msgs]
    (letfn [(get-msg []
              (let [msg (byte-vec->str (recvpkt in-pipe))]
                ;; Send an ACK
                (sendpkt out-pipe [1])
                msg))]
      (vec (doall (repeatedly n-msgs get-msg))))))

(defn count-errors
  ([L1 L2] (count-errors L1 L2 0))
  ([L1 L2 errors]
   (cond
     (empty? L1) (+ errors (count L2))
     (empty? L2) (+ errors (count L1))
     :default
     (recur (pop L1) (pop L2) (if (= (peek L1) (peek L2)) errors (inc errors))))))

(defn parse-protocol-str
  [protocol-str]
  (case (clojure.string/lower-case protocol-str)
    "naive" [->NaiveSender ->NaiveReceiver]
    (do (println (str "Error, unknown protocol " protocol-str))
        (System/exit 2))))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (if-not (= (count args) 2)
    (do
      (println "Usage: guaranteed-delivery inputfile protocol")
      (System/exit 1)))
  (let [[make-sender make-receiver] (parse-protocol-str (second args))
        sender->receiver (make-pipe)
        receiver->sender (make-pipe)
        msgs (vec (clojure.string/split-lines (slurp (first args))))
        sender (make-sender receiver->sender sender->receiver)
        receiver (make-receiver sender->receiver receiver->sender)
        start-time (System/currentTimeMillis)]
    (println (str "Sending " (count msgs) " messages."))
    (.start (Thread. #(send-msgs sender msgs)))
    (println (str (count-errors msgs (recv-msgs receiver (count msgs))) " errors."))
    (println (str "Sending took " (- (System/currentTimeMillis) start-time) "ms."))))


