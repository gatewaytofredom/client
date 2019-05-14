(ns wh.jobs.job.events
  (:require
    [cljs-time.coerce :as tc]
    [cljs-time.core :as t]
    [cljs-time.format :as tf]
    [clojure.string :as str]
    [re-frame.core :refer [dispatch path reg-event-db reg-event-fx]]
    [wh.common.cases :as cases]
    [wh.common.fx.google-maps]
    [wh.common.issue :refer [gql-issue->issue]]
    [wh.common.job :as common-job]
    [wh.db :as db]
    [wh.graphql.jobs :as graphql-jobs]
    [wh.jobs.job.db :as job]
    [wh.pages.core :refer [on-page-load]]
    [wh.user.db :as user]
    [wh.util :as util])
  (:require-macros
    [clojure.core.strint :refer [<<]]
    [wh.graphql-macros :refer [deffragment defquery def-query-template def-query-from-template]]))

(def job-interceptors (into db/default-interceptors
                            [(path ::job/sub-db)]))

(defquery update-job-mutation
  {:venia/operation {:operation/type :mutation
                     :operation/name "update_job"}
   :venia/variables [{:variable/name "update_job"
                      :variable/type :UpdateJobInput!}]
   :venia/queries   [[:update_job {:update_job :$update_job}
                      [:id :published]]]})

(deffragment jobFields :Job
  [:id :title :companyName :companyId :tagline :descriptionHtml :logo :tags :benefits :roleType :manager
   ;; [:company [[:issues [:id :title [:labels [:name]]]]]] ; commented out until company is leonaized
   [:location [:street :city :country :countryCode :state :postCode :longitude :latitude]]
   [:remuneration [:competitive :currency :timePeriod :min :max :equity]]
   :locationDescription :companyDescriptionHtml :remote :sponsorshipOffered :applied :published])

(def-query-template job-query
  {:venia/operation {:operation/type :query
                     :operation/name "job"}
   :venia/variables [{:variable/name "id"
                      :variable/type :ID!}]
   :venia/queries [[:job {:id :$id} $fields]]})

(def-query-from-template job-query--default job-query
  {:fields :fragment/jobFields})

(def-query-from-template job-query--company job-query
  {:fields [[:fragment/jobFields] :matchingUsers]})

(def-query-from-template job-query--candidate job-query
  {:fields [[:fragment/jobFields] :userScore]})

(defn job-query [db]
  (cond
    (user/company? db) job-query--company
    (user/candidate? db) job-query--candidate
    :otherwise job-query--default))

(defquery company-query
  {:venia/operation {:operation/name "jobCompany"
                     :operation/type :query}
   :venia/variables [{:variable/name "id"
                      :variable/type :ID!}]
   :venia/queries [[:company {:id :$id}
                    [:id :permissions :package [:pendingOffer [:recurringFee]]]]]})

(defquery issues-query
  {:venia/operation {:operation/name "jobIssues"
                     :operation/type :query}
   :venia/variables [{:variable/name "id"
                      :variable/type :ID}]
   :venia/queries [[:query_issues {:company_id :$id, :page_size 2}
                    [[:issues [:id :title :level [:repo [:primary_language]]]]]]]})

(defn translate-job [job]
  (as-> job job
        (cases/->kebab-case job)
        (util/namespace-map "wh.jobs.job.db" job)))

(reg-event-fx
  ::fetch-company-issues
  job-interceptors
  (fn [{db :db} [res]]
    {:graphql {:query issues-query
               :variables {:id (::job/company-id db)}
               :on-success [::fetch-company-issues-success]
               :on-failure [::fetch-company-issues-failure]}}))

(reg-event-db
  ::fetch-company-issues-success
  job-interceptors
  (fn [db [resp]]
    (assoc-in db [::job/company :issues]
              (->> (get-in resp [:data :query_issues :issues])
                   (map gql-issue->issue)))))

(reg-event-fx
  ::fetch-company-issues-failure
  job-interceptors
  (fn [{db :db} [_]]
    {})) ;; better to display no issues than worry them

(defquery job-analytics-query
  {:venia/operation {:operation/name "jobAnalytics"
                     :operation/type :query}
   :venia/variables [{:variable/name "company_id"
                      :variable/type :ID}
                     {:variable/name "job_id"
                      :variable/type :ID}
                     {:variable/name "end_date"
                      :variable/type :String}]
   :venia/queries [[:job_analytics {:company_id :$company_id
                                    :job_id :$job_id
                                    :end_date :$end_date
                                    :granularity 0
                                    :num_periods 0}
                    [:granularity
                     [:applications [:date :count]]
                     [:views [:date :count]]
                     [:likes [:date :count]]]]]})

(reg-event-fx
  ::fetch-job-analytics
  db/default-interceptors
  (fn [{db :db} [job-id company-id]]
    {:graphql {:query job-analytics-query
               :variables {:company_id company-id
                           :job_id job-id
                           :end_date (tf/unparse (tf/formatters :date) (t/now))}
               :on-success [::fetch-job-analytics-success]
               :on-failure [::fetch-job-analytics-failure]}}))

(reg-event-db
  ::fetch-job-analytics-success
  job-interceptors
  (fn [db [res]]
    (assoc db ::job/analytics (get-in res [:data :job_analytics]))))

(reg-event-fx
  ::fetch-job-analytics-failure
  job-interceptors
  (fn [{db :db} [_res]]))

(reg-event-fx
  ::fetch-job-success
  db/default-interceptors
  (fn [{db :db} [res]]
    (let [job (translate-job (get-in res [:data :job]))]
      (merge
        {:db (update db ::job/sub-db
                     merge job
                     {::job/error nil
                      ::job/preset-id (::job/id job)})};; the current job is now the preset one
        (when (user/admin? db)
          {:dispatch [::fetch-company-issues]})
        (if (or (user/admin? db)
                (and (user/company? db)
                     (= (::job/company-id job) (user/company-id db))))
          {:graphql {:query company-query
                     :variables {:id (::job/company-id job)}
                     :on-success [::fetch-company-success]
                     :on-failure [::fetch-company-failure]}})))))

(reg-event-fx
  ::fetch-company-success
  db/default-interceptors
  (fn [{db :db} [res]]
    (let [company (-> res
                      (get-in [:data :company])
                      (update :permissions #(set (map keyword %)))
                      (cases/->kebab-case))]
      (merge {:db (update-in db [::job/sub-db ::job/company] merge company)}
             (when (user/admin? db)
               {:dispatch [::fetch-job-analytics (get-in db [::job/sub-db ::job/id]) (:id company)]})))))

(reg-event-fx
  ::fetch-failure
  job-interceptors
  (fn [{db :db} [res]]
    {:db (assoc db ::job/error (util/gql-errors->error-key res))}))

(reg-event-fx
  ::fetch-company-failure
  job-interceptors
  (fn [{db :db} [res]]
    {:dispatch [:error/set-global "There was an error fetching company information."]}))

(reg-event-fx
  ::fetch-job
  db/default-interceptors
  (fn [{db :db} [id]]
    {:scroll-to-top true
     :graphql {:query (job-query db)
               :variables {:id id}
               :on-success [::fetch-job-success]
               :on-failure [::fetch-failure]}}))

(reg-event-db
  ::reset-error
  db/default-interceptors
  (fn [db _]
    (assoc-in db [::db/sub-db ::job/error] nil)))

(reg-event-db
  ::set-applied
  db/default-interceptors
  (fn [db _]
    (let [job-id (get-in db [:wh.logged-in.apply.db/sub-db :wh.logged-in.apply.db/job :id])]
      (update-in db [::user/sub-db ::user/applied-jobs] conj job-id))))

(reg-event-db
  ::set-applications-sort
  job-interceptors
  (fn [db [column direction]]
    (assoc db ::job/applications-sort {:column column, :direction direction})))

(reg-event-db
  ::show-like-icon
  job-interceptors
  (fn [db [value]]
    (assoc db ::job/like-icon-shown? value)))

(reg-event-fx
  :google/load-maps
  (fn [{db :db} _]
    {:google/load-maps #(dispatch [:google/maps-loaded])}))

(reg-event-db
  :google/maps-loaded
  (fn [db _]
    (assoc db :google/maps-loaded? true)))

(reg-event-db
  ::publish-job-success
  job-interceptors
  (fn [db [job-id]]
    (assoc db
           ::job/publishing? false
           ::job/published true)))

(reg-event-db
  ::publish-job-failure
  job-interceptors
  (fn [db [job-id]]
    (assoc db ::job/publishing? false)))

(defn navigate-to-payment-setup
  [job-id]
  {:navigate [:payment-setup :params {:step :select-package} :query-params {:job job-id :action :publish}]})

(defn publish-job
  [db job-id {:keys [success failure retry]}]
  {:graphql {:query      update-job-mutation
             :variables  {:update_job
                          (cond-> {:id job-id
                                   :published true}
                            (user/admin? db) (assoc :approved true))}
             :on-success success
             :on-failure [::publish-job-failure-internal job-id failure retry]}})

(reg-event-fx
  ::publish-job-failure-internal
  db/default-interceptors
  (fn [{db :db} [job-id failure-event retry-event resp]]
    (let [reason (some-> resp :errors first :key keyword)]
      (case reason
        :invalid-job
        {:navigate [:edit-job :params {:id job-id} :query-params {:save 1}]
         :dispatch failure-event}
        :missing-permission
        (if (user/admin? db)
          ;; TODO admin popup could go here instead...
          (do (js/alert "Company does not have permission to publish this role. Check their package.")
              {:dispatch failure-event})
          (navigate-to-payment-setup job-id))
        {:dispatch-n [[:error/set-global "There was an error publishing the role."
                       retry-event]
                      failure-event]}))))

(reg-event-db
  ::show-admin-publish-prompt?
  job-interceptors
  (fn [db [show?]]
    (assoc db ::job/show-admin-publish-prompt? show?)))

(defn show-admin-publish-prompt!
  []
  {:dispatch [::show-admin-publish-prompt? true]})

(defn proccess-publish-role-intention
  [{:keys [db job-id permissions publish-events on-publish pending-offer]}]
  (cond
    (or (contains? permissions :can_publish)
        (and (user/admin? db) pending-offer))
    (merge (publish-job db job-id publish-events)
           {:db (on-publish db)})

    (user/admin? db)
    (show-admin-publish-prompt!)

    :else
    (navigate-to-payment-setup job-id)))

(reg-event-fx
  ::publish-role
  db/default-interceptors
  (fn [{db :db} [job-id]]
    (let [perms (job/company-permissions db)
          job-id (or job-id (get-in db [::job/sub-db ::job/id]))
          pending-offer (get-in db [::job/sub-db ::job/company :pending-offer])]
      (proccess-publish-role-intention
       {:db db
        :job-id job-id
        :permissions perms
        :pending-offer pending-offer
        :publish-events {:success [::publish-job-success]
                         :failure [::publish-job-failure]
                         :retry   [::publish-role]}
        :on-publish #(assoc-in % [::job/sub-db ::job/publishing?] true)}))))

(defquery update-company
  {:venia/operation {:operation/type :mutation
                     :operation/name "adminSetCompanyToFree"}
   :venia/variables [{:variable/name "update_company"
                      :variable/type :UpdateCompanyInput!}]
   :venia/queries   [[:update_company {:update_company :$update_company}
                      [:id :package :permissions]]]})

(reg-event-fx
  ::admin-set-company-to-free
  job-interceptors
  (fn [{db :db} [company-id success-event]]
    {:db (-> db
             (assoc ::job/admin-publish-prompt-loading? true))
     :graphql {:query update-company
               :variables  {:update_company
                            {:id      company-id
                             :package :free}}
               :on-success [::update-company-success success-event]
               :on-failure [::update-company-failure]}}))

(reg-event-fx
  ::update-company-success
  job-interceptors
  (fn [{db :db} [success-event resp]]
    (let [company (-> resp
                      (get-in [:data :update_company])
                      (update :package keyword)
                      (update :permissions #(set (map keyword %))))]
      (merge {:db (-> db
                      (assoc ::job/admin-publish-prompt-loading? false)
                      (update ::job/company merge company))}
             (if success-event
               {:dispatch-n [[::show-admin-publish-prompt? false]
                             (conj success-event company)]}
               {:dispatch [::show-admin-publish-prompt? false]})))))

(reg-event-fx
  ::update-company-failure
  job-interceptors
  (fn [{db :db} [resp]]
    {:db (assoc db ::job/admin-publish-prompt-loading? false)
     :dispatch [:error/set-global "Failed to update the company!"]}))

(reg-event-fx
  ::edit-apply-on-behalf
  job-interceptors
  (fn [{db :db} [value]]
    {:db                (cond-> (assoc db ::job/apply-on-behalf value
                                       ::job/applied-on-behalf false
                                       ::job/apply-on-behalf-error nil)
                          (str/blank? value) (assoc ::job/apply-on-behalf-id nil))
     :dispatch-debounce {:id       :search-candidates-for-apply-on-behalf
                         :dispatch [::search-candidates]
                         :timeout  100}}))

(reg-event-fx
  ::search-candidates
  job-interceptors
  (fn [{db :db} [retry-num]]
    {:algolia {:index      :candidates
               :params     {:query       (::job/apply-on-behalf db)
                            :page        0
                            :hitsPerPage 10
                            :facets      "[\"name\",\"email\"]"}
               :on-success [::search-candidates-success]
               :on-failure [::search-bad-response retry-num]}}))

(reg-event-db
  ::select-apply-on-behalf
  job-interceptors
  (fn [db [id]]
    (assoc db
           ::job/apply-on-behalf (:text-label (first (filter #(= (:id %) id) (::job/apply-on-behalf-suggestions db))))
           ::job/apply-on-behalf-id id
           ::job/apply-on-behalf-suggestions nil)))

(defn translate-candidate
  [{{:keys [email name]} :_highlightResult, id :objectID, :as obj}]
  {:id id,
   :text-label (<< "~(:name obj) (~(:email obj))")
   :label [:span.candidate
           [:span.name {:dangerouslySetInnerHTML {:__html (:value name)}}]
           [:span.email {:dangerouslySetInnerHTML {:__html (:value email)}}]]})

(reg-event-db
  ::search-candidates-success
  job-interceptors
  (fn [db [{:keys [hits facets page nbPages]} :as results]]
    (assoc db ::job/apply-on-behalf-suggestions (mapv translate-candidate hits))))

(reg-event-db
  ::show-notes-overlay
  job-interceptors
  (fn [db _]
    (assoc db ::job/show-notes-overlay? true)))

(reg-event-db
  ::hide-notes-overlay
  job-interceptors
  (fn [db _]
    (assoc db ::job/show-notes-overlay? false)))

(reg-event-db
  ::edit-note
  job-interceptors
  (fn [db [value]]
    (assoc db ::job/note value)))

(defquery apply-mutation
  {:venia/operation {:operation/type :mutation
                     :operation/name "ApplyAs"}
   :venia/variables [{:variable/name "id"
                      :variable/type :String!}
                     {:variable/name "userId"
                      :variable/type :String!}
                     {:variable/name "note"
                      :variable/type :String!}]
   :venia/queries [[:applyAs {:id :$id :userId :$userId :note :$note}]]})

(reg-event-fx
  ::apply-on-behalf
  job-interceptors
  (fn [{{:keys [::job/id ::job/apply-on-behalf-id ::job/note] :as db} :db} _]
    {:graphql {:query      apply-mutation
               :variables  {:id     id
                            :userId apply-on-behalf-id
                            :note   (or note "")}
               :on-success [::apply-on-behalf-success]
               :on-failure [::apply-on-behalf-failure]}
     :db (assoc db
                ::job/apply-on-behalf-error nil
                ::job/applying? true)}))

(reg-event-db
  ::apply-on-behalf-success
  job-interceptors
  (fn [db _]
    (assoc db
           ::job/applied-on-behalf true
           ::job/applying? false
           ::job/note nil
           ::job/apply-on-behalf nil
           ::job/apply-on-behalf-id nil)))

(reg-event-db
  ::apply-on-behalf-failure
  job-interceptors
  (fn [db [resp]]
    (assoc db
           ::job/applied-on-behalf false
           ::job/applying? false
           ::job/apply-on-behalf-error (util/gql-errors->error-key resp))))

(reg-event-db
  ::expand-job-description
  job-interceptors
  (fn [db _]
    (assoc db ::job/job-description-expanded? true)))

(reg-event-db
  ::expand-company-description
  job-interceptors
  (fn [db _]
    (assoc db ::job/company-description-expanded? true)))

(reg-event-db
  ::expand-location-description
  job-interceptors
  (fn [db _]
    (assoc db ::job/location-description-expanded? true)))

(reg-event-fx
  ::fetch-recommended-jobs
  job-interceptors
  (fn [{db :db} [id]]
    {:graphql {:query      graphql-jobs/recommended-jobs-for-job
               :variables  {:job_id id}
               :on-failure [::fetch-recommended-jobs-failure]
               :on-success [::fetch-recommended-jobs-success]}}))

(reg-event-fx
  ::fetch-recommended-jobs-success
  job-interceptors
  (fn [{db :db} [{{:keys [jobs]} :data}]]
    {:db (assoc db ::job/recommended-jobs (mapv common-job/translate-job jobs))}))

(reg-event-fx
  ::fetch-recommended-jobs-failure
  job-interceptors
  (fn [{db :db} _] {:db (assoc db ::job/recommended-jobs [])}))

(reg-event-fx
  ::set-show-apply-sticky?
  job-interceptors
  (fn [{db :db} [show?]]
    {:db (assoc db ::job/show-apply-sticky? show?)}))

;; We do this so that as we're fetching the job there are
;; some fields we already have and therefore can "prefill"
(reg-event-db
  :wh.job/preset-job-data
  job-interceptors
  (fn [db [{:keys [id] :as job}]]
    (let [preset-fields (select-keys job [:company-name :title :location :logo :tagline :tags
                                          :benefits :remote :remuneration :role-type])]
      (merge (if (= id (::job/preset-id db))
               db
               job/default-db)
             {::job/preset-id id}
             (util/namespace-map "wh.jobs.job.db" preset-fields)))))

(reg-event-db
  ::initialize-db
  job-interceptors
  (fn [_ _] job/default-db))

(reg-event-fx
  ::load-company-module-if-needed
  db/default-interceptors
  (fn [{db :db} _]
    (if (or (user/admin? db) (user/company? db))
      {:load-and-dispatch [:company]}
      {})))

(defmethod on-page-load :job [db]
  (let [requested-id (get-in db [::db/page-params :id])
        id-in-db (get-in db [::job/sub-db ::job/id])
        preset-id (get-in db [::job/sub-db ::job/preset-id])]
    ;; If you are changing below logic make sure that wh.response.handler.job is also updated
    [[::load-company-module-if-needed]
     (when (or (and preset-id (not= requested-id preset-id))
               (and (not preset-id) (not (::db/initial-load? db))))
       [::initialize-db])
     (if (not= requested-id id-in-db)
       [::fetch-job requested-id]
       (when (user/admin? db)
         [::fetch-company-issues]))
     (when (user/company? db)
       [::fetch-job-analytics requested-id (user/company-id db)])
     (when (= (get-in db [::db/query-params "apply"]) "true")
       [:apply/try-apply {:id requested-id}])
     [::fetch-recommended-jobs requested-id]
     [:google/load-maps]]))