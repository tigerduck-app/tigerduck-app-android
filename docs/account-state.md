# Account state

What the phone app keeps on the device, by account, and what clears it when that account signs
out. The school account, School Mail and the library account each sign in and out on their own
(decided for Android on 2026-10-22), so a school sign-out leaves the other two. The school
account's device-side clearing is `AuthService.logout()`, together with
`AccountSessionEffects.onSignedOut`, which follows every school sign-out wherever it came from.
School Mail's is `MailAccount.signOut()`, and the library's is `SettingsViewModel.logoutLibrary()`.

`AccountStateInventoryTest` reads the tables of stored state below and fails in these cases:

- a store the app declares has no row: a SharedPreferences or DataStore file it opens, a key of
  such a file, a file or folder DataCache keeps, or a database it names;
- the sources open a store under a name the check cannot work out, or read or write a key it
  cannot read;
- a row names a store that is no longer declared;
- a file that outlives the accounts is listed as a whole: its keys are listed instead (scope
  `keys`), so a key of an account added to it later gets a row of its own;
- a row of an account names nothing that clears it, or the function it names no longer clears
  it:
  - a key is cleared by a `remove` of it or by setting it to an empty value, named directly,
    through a constant that holds it, or through a function or property declared with it;
  - a DataCache file is cleared by a statement that names it and deletes;
  - a file an account owns whole is cleared by a function of a source that opens it, which
    clears the file or removes each of its keys;
  - with `via`, the function must make that call instead.

The check reads a key passed through a parameter at its constant: a source of one file names
those `KEY_…`, `K_…` or `…_KEY`.

`AccountSwitchTest` starts with the first student's data on the device:

- every key the rows give the school account, typed as its source reads it;
- every file the rows give to DataCache, in the folder DataCache keeps it in;
- a bulletin snapshot and an imported class table, each with a write cut short beside it, and
  the in-app browser's data.

It then signs that student out with the real `AuthService.logout()` and `AccountSessionEffects`.
It signs a second student in through `AuthService.login()`, against hand-made school pages, and
checks that nothing of the first is left. The first student's School Mail and library account
stay.

Scopes:

- `school`: the school account. Cleared at its sign-out.
- `mail`: School Mail. Cleared at its sign-out.
- `library`: the library account. Cleared at its sign-out.
- `owed`: the device unregister a school sign-out still owes. It outlasts the sign-out until the
  server has it (`DepartingDeviceUnregister`), and is cleared then.
- `device`: outlives every account: settings, the device's own identity and state, public school
  data and the debug tools.
- `keys`: a file listed by its keys, each with a row of its own.

## Stores the check cannot read

| Store | Cleared by |
|---|---|
| The school session's cookies, in memory | `NtustSessionManager.signOut()` in `AuthService.logout()` |
| Answers shared between callers, in memory: the course list, Moodle's enrollments and assignments, the empty-classroom days | `CourseService.cancelSharedFetches()`, `MoodleService.cancelSharedFetches()` and `forgetEmptyClassroom` in `AuthService.logout()` |
| Bulletin snapshots, `filesDir/bulletins/`, and what a write a process death cut short left there | `BulletinCache.clear()` in `AuthService.logout()` |
| Imported class tables, `noBackupFilesDir/imported_class_tables.json`, and the `.tmp` a write a process death cut short left beside it | `ImportedClassTableStore.clearForSignOut()` in `AuthService.logout()`; another student's sign-in empties it too (`accountDidSignIn`) |
| A share link held for a sign-in, in memory | `HeldShareLink.drop()` in `AuthService.logout()` |
| The demo account | `DemoAccount.exit()` in `AuthService.logout()` |
| The in-app browser's WebView data, cookies, downloads and photo uploads (`cacheDir/browser_uploads/`) | `BrowserDataCleaner.clear()`, which `AccountSessionEffects` runs after each sign-out and sign-in; `BrowserCleanup` keeps it owed across a process death |
| Delivered notifications of the school account | `AccountNotifications.cancelAll()` in `AccountSessionEffects.onSignedOut` |
| Assignment reminders' alarms | `AssignmentNotificationScheduler.cancelAllTracked()` in `AccountSessionEffects.onSignedOut` |
| Class-preparing alarms and the Live Update | `LiveActivityManager.stop()` in `AccountSessionEffects.onSignedOut` |
| Background sync work, WorkManager's `homework_refresh_periodic` and `sync_trigger` | `BackgroundSyncWorker.cancel()` in `AccountSessionEffects.onSignedOut` |
| The backend's device registration | `PushRegistrationService.unregister()` in `AuthService.logout()`, sent with the departing session until it lands: WorkManager's `departing_device_unregister` and the `owed` rows below |
| Widgets | `WidgetUpdater.updateAll()`, the last step of `AuthService.logout()`'s clearing |
| The watch's schedule | `WearScheduleBridge.publish()`, which `TigerDuckApp` runs at each sign-in and sign-out and which sends no courses while no one is signed in; the library account on the watch follows the library's own sign-out |
| School Mail's cache (`cacheDir/mail/`), the mail held to send (`noBackupFilesDir/mail_held_sends/`), its checks (WorkManager) and its notifications | `MailAccount.signOut()` |
| The library's pass code, in memory | `LibrarySession.refresh()`, which `SettingsViewModel.logoutLibrary()` calls |
| Entries taken out of an opened archive, `cacheDir/archive_entries/` | deleted when the viewer closes; a folder a process death left goes once it is a day old, when the next archive opens; not at a sign-out |
| Exported class table images, `cacheDir/class_table_export/` | kept a day so a share target can still read one, then swept by the next export; not at a sign-out |
| The TLS pin document, `noBackupFilesDir/tls/` | device |
| The debug viewer samples, `cacheDir/viewer_samples/` | device |

## SharedPreferences and DataStore files

| Store | Name | Scope | Cleared in |
|---|---|---|---|
| prefs | `academic_calendar` | keys | |
| prefs | `bulletin_read_state` | school | `BulletinReadStateStore.kt` `clear` |
| prefs | `cjk_centering` | keys | |
| prefs | `course_changes` | keys | |
| prefs | `class_preparing_tracker` | keys | |
| prefs | `debug_clock` | keys | |
| prefs | `debug_fixture` | keys | |
| prefs | `debug_outside_apps` | keys | |
| prefs | `moodle_files` | keys | |
| prefs | `notification_tracker` | school | `AssignmentNotificationScheduler.kt` `cancelAllTracked` |
| prefs | `push_diagnostics` | keys | |
| prefs | `school_mail_dev_server` | keys | |
| prefs | `school_mail_state` | mail | `MailStateStore.kt` `clear` |
| prefs | `server_push` | keys | |
| prefs | `server_push_intent` | keys | |
| prefs | `tigerduck_auth` | keys | |
| prefs | `tigerduck_browser_allowlist` | keys | |
| prefs | `tigerduck_browser_cleanup` | keys | |
| prefs | `tigerduck_browser_settings` | keys | |
| prefs | `tigerduck_credentials` | keys | |
| prefs | `tigerduck_live_activity` | keys | |
| prefs | `tigerduck_permissions` | keys | |
| prefs | `tigerduck_prefs` | keys | |
| prefs | `tigerduck_sync` | school | `AppPreferences.kt` `forgetLegacyLastCourseSyncAt` |
| prefs | `tls_pin_acceptances` | keys | |
| prefs | `wear_bridge_state` | keys | |

A file listed with an account's scope is cleared whole, and its keys have no rows.

## Keys of `tigerduck_prefs`

`AppPreferences`, with the display sizes `DisplayScale` names and the device ID `PushIdentity`
keeps through `getOrCreateString`.

| Store | Name | Scope | Cleared in |
|---|---|---|---|
| tigerduck_prefs | `accentColorHex` | device | |
| tigerduck_prefs | `accentGradientDirection` | device | |
| tigerduck_prefs | `accentGradientEndHex` | device | |
| tigerduck_prefs | `accentGradientStartHex` | device | |
| tigerduck_prefs | `alwaysShowPeriodsABC` | device | |
| tigerduck_prefs | `analyticsEnabled` | device | |
| tigerduck_prefs | `announcementApiBaseUrlOverride` | device | |
| tigerduck_prefs | `appLanguage` | device | |
| tigerduck_prefs | `appTextScale` | device | |
| tigerduck_prefs | `appUiScale` | device | |
| tigerduck_prefs | `assignmentMarksSyncedOnce` | school | `AppPreferences.kt` `clearSyncStamps` |
| tigerduck_prefs | `assignmentsUploadFingerprint` | school | `AppPreferences.kt` `clearSyncStamps` |
| tigerduck_prefs | `assignmentsUploadedAtMs` | school | `AppPreferences.kt` `clearSyncStamps` |
| tigerduck_prefs | `backgroundCoursesSyncedAtMs` | school | `AppPreferences.kt` `clearSyncStamps` |
| tigerduck_prefs | `backgroundCoursesTerm` | school | `AppPreferences.kt` `clearSyncStamps` |
| tigerduck_prefs | `backgroundOverridesSyncedAtMs` | school | `AppPreferences.kt` `clearSyncStamps` |
| tigerduck_prefs | `browserPreference` | device | |
| tigerduck_prefs | `classReminderEnabled` | device | |
| tigerduck_prefs | `classReminderLeadMinutes` | device | |
| tigerduck_prefs | `classReminderSettingsMigrated` | device | |
| tigerduck_prefs | `classTableSelectedSemester` | school | `AuthService.kt` `logout` |
| tigerduck_prefs | `classroomMandarinDisplay` | device | |
| tigerduck_prefs | `cloudSyncEnabled` | device | |
| tigerduck_prefs | `configuredTabs` | device | |
| tigerduck_prefs | `courseNameScale` | device | |
| tigerduck_prefs | `coursesUploadFingerprint:*` | school | `AppPreferences.kt` `removeCourseUploads` |
| tigerduck_prefs | `coursesUploadRoster:*` | school | `AppPreferences.kt` `removeCourseUploads` |
| tigerduck_prefs | `coursesUploadedAtMs:*` | school | `AppPreferences.kt` `removeCourseUploads` |
| tigerduck_prefs | `dataSchemaVersion` | device | |
| tigerduck_prefs | `disableScreenCaptureProtection` | device | |
| tigerduck_prefs | `emptyClassroomBuilding` | school | `EmptyClassroomSignOut.kt` `forgetEmptyClassroom` |
| tigerduck_prefs | `firstTriggerPromptSeen.*` | device | |
| tigerduck_prefs | `flipToLibraryDefaultOffChecked` | device | |
| tigerduck_prefs | `flipToLibraryEnabled` | device | |
| tigerduck_prefs | `haptic_duration_*` | device | |
| tigerduck_prefs | `haptic_strength_*` | device | |
| tigerduck_prefs | `hasCompletedOnboarding` | device | |
| tigerduck_prefs | `hasPickedClassReminderLead` | device | |
| tigerduck_prefs | `hasUnconfirmedAssignmentSyncEdit` | school | `NotificationSettingsSync.kt` `cancelPendingPushes` via `assignmentStore.hasUnconfirmedEdit = false` |
| tigerduck_prefs | `hasUnconfirmedClassReminderSyncEdit` | school | `NotificationSettingsSync.kt` `cancelPendingPushes` via `classReminderStore.hasUnconfirmedEdit = false` |
| tigerduck_prefs | `holidayNotifyOverrides` | school | `AppPreferences.kt` `forgetHolidayOverrides` |
| tigerduck_prefs | `holidayOverridesAwaitingUpload` | school | `AppPreferences.kt` `forgetHolidayOverrides` |
| tigerduck_prefs | `homeAssignmentFilter` | device | |
| tigerduck_prefs | `homeSections` | device | |
| tigerduck_prefs | `invertSliderDirection` | device | |
| tigerduck_prefs | `lastSeenCoursesResetAt` | school | `AppPreferences.kt` `clearSyncStamps` |
| tigerduck_prefs | `lastSeenWhatsNewVersionCode` | device | |
| tigerduck_prefs | `lastUpdatePromptEpoch` | device | |
| tigerduck_prefs | `lastUpdatePromptVersionCode` | device | |
| tigerduck_prefs | `legacyNotificationChannelsDeleted` | device | |
| tigerduck_prefs | `libraryFeatureEnabled` | device | |
| tigerduck_prefs | `librarySyncedAtMs` | library | `AppPreferences.kt` `forgetLibrarySynced` |
| tigerduck_prefs | `libraryTabKeepChecked` | device | |
| tigerduck_prefs | `mailBarQuestionAsked` | device | |
| tigerduck_prefs | `moodleOpenIn` | device | |
| tigerduck_prefs | `notifyAssignmentOffsets` | device | |
| tigerduck_prefs | `notifyAssignments` | device | |
| tigerduck_prefs | `onboardingVersion` | device | |
| tigerduck_prefs | `outlineRequiredCourses` | device | |
| tigerduck_prefs | `pendingConflictCategories` | school | `AuthService.kt` `logout` |
| tigerduck_prefs | `pinTodayCoursesOnClassTable` | device | |
| tigerduck_prefs | `previousDefaultTabsChecked` | device | |
| tigerduck_prefs | `push.device_uuid` | device | |
| tigerduck_prefs | `rememberAnnouncementFilter` | device | |
| tigerduck_prefs | `reviewPromptAsked` | device | |
| tigerduck_prefs | `reviewPromptDaysUsed` | device | |
| tigerduck_prefs | `reviewPromptLastDay` | device | |
| tigerduck_prefs | `rotationMode` | device | |
| tigerduck_prefs | `savedAnnouncementOrgs` | device | |
| tigerduck_prefs | `schoolDataSyncedAtMs` | school | `AppPreferences.kt` `clearSyncStamps` |
| tigerduck_prefs | `semesterCatalogRefreshedAt` | device | |
| tigerduck_prefs | `semesterCatalogSelection` | device | |
| tigerduck_prefs | `semesterCatalogTerms` | device | |
| tigerduck_prefs | `showAbsoluteAssignmentTime` | device | |
| tigerduck_prefs | `showClassroomInClassTable` | device | |
| tigerduck_prefs | `skippedUpdateVersionCode` | device | |
| tigerduck_prefs | `ssoLoginTimestamp` | school | `AppPreferences.kt` `clearSsoTimestamp` |
| tigerduck_prefs | `syncAssignmentReminders` | device | |
| tigerduck_prefs | `syncAssignments` | device | |
| tigerduck_prefs | `syncCourseColors` | device | |
| tigerduck_prefs | `syncCourseNames` | device | |
| tigerduck_prefs | `syncCourses` | device | |
| tigerduck_prefs | `syncLiveActivity` | device | |
| tigerduck_prefs | `syncedCategories` | school | `AppPreferences.kt` `clearSyncStamps` |
| tigerduck_prefs | `themeMode` | device | |
| tigerduck_prefs | `useEnglishClassroomAbbreviation` | device | |
| tigerduck_prefs | `useEnglishCourseAbbreviation` | device | |
| tigerduck_prefs | `useWallpaperColors` | device | |
| tigerduck_prefs | `visualPreset` | device | |
| tigerduck_prefs | `widgetTextScale` | device | |
| tigerduck_prefs | `widgetUiScale` | device | |

`rememberAnnouncementFilter`, `savedAnnouncementOrgs` and `libraryFeatureEnabled` are retired:
`AppPreferences` removes the first two when it starts, and `LibraryTabKeepMigration` the third.
The semester catalog is the school's own list of terms.

## Keys of `tigerduck_credentials`

The encrypted store `CredentialManager` opens, with the backend's tokens (`V3TokenStore`) and the
unregister a sign-out still owes (`PendingUnregisterPrefs`).

| Store | Name | Scope | Cleared in |
|---|---|---|---|
| tigerduck_credentials | `departing_access_token` | owed | `PendingUnregisterPrefs.kt` `clear` |
| tigerduck_credentials | `departing_base_url` | owed | `PendingUnregisterPrefs.kt` `clear` |
| tigerduck_credentials | `departing_device_id` | owed | `PendingUnregisterPrefs.kt` `clear` |
| tigerduck_credentials | `departing_expires_at` | owed | `PendingUnregisterPrefs.kt` `clear` |
| tigerduck_credentials | `departing_owed_at` | owed | `PendingUnregisterPrefs.kt` `clear` |
| tigerduck_credentials | `departing_refresh_token` | owed | `PendingUnregisterPrefs.kt` `clear` |
| tigerduck_credentials | `departing_student_id` | owed | `PendingUnregisterPrefs.kt` `clear` |
| tigerduck_credentials | `departing_unanswered_refreshes` | owed | `PendingUnregisterPrefs.kt` `clear` |
| tigerduck_credentials | `library_password` | library | `CredentialManager.kt` `clearLibraryCredentials` |
| tigerduck_credentials | `library_password_refusals` | library | `CredentialManager.kt` `clearLibraryCredentials` |
| tigerduck_credentials | `library_password_refused` | library | `CredentialManager.kt` `takeUpLibraryLatch` |
| tigerduck_credentials | `library_password_refused_at` | library | `CredentialManager.kt` `clearLibraryCredentials` |
| tigerduck_credentials | `library_token` | library | `CredentialManager.kt` `clearLibraryCredentials` |
| tigerduck_credentials | `library_token_expiry` | library | `CredentialManager.kt` `clearLibraryCredentials` |
| tigerduck_credentials | `library_username` | library | `CredentialManager.kt` `clearLibraryCredentials` |
| tigerduck_credentials | `mail_password` | mail | `CredentialManager.kt` `clearMailCredentials` |
| tigerduck_credentials | `mail_student_id` | mail | `CredentialManager.kt` `clearMailCredentials` |
| tigerduck_credentials | `moodle_private_token` | school | `CredentialManager.kt` `clearNtustCredentials` |
| tigerduck_credentials | `moodle_token` | school | `CredentialManager.kt` `clearNtustCredentials` |
| tigerduck_credentials | `ntust_password` | school | `CredentialManager.kt` `clearNtustCredentials` |
| tigerduck_credentials | `ntust_student_id` | school | `CredentialManager.kt` `clearNtustCredentials` |
| tigerduck_credentials | `v3_access_token` | school | `V3TokenStore.kt` `clear` |
| tigerduck_credentials | `v3_issued_by` | school | `V3TokenStore.kt` `clear` |
| tigerduck_credentials | `v3_refresh_token` | school | `V3TokenStore.kt` `clear` |
| tigerduck_credentials | `v3_refresh_token_sends` | school | `V3TokenStore.kt` `clear` |
| tigerduck_credentials | `v3_student_id` | school | `V3TokenStore.kt` `clear` |
| tigerduck_credentials | `v3_token_expires_at` | school | `V3TokenStore.kt` `clear` |

`library_password_refused` is the refusal flag of builds before the count; the store takes it up
into `library_password_refusals` when it opens.

## Keys of the other files

| Store | Name | Scope | Cleared in |
|---|---|---|---|
| academic_calendar | `etag` | device | |
| academic_calendar | `payload` | device | |
| cjk_centering | `corrections` | device | |
| cjk_centering | `key` | device | |
| course_changes | `endpoint` | device | |
| course_changes | `etag` | device | |
| course_changes | `payload` | device | |
| class_preparing_tracker | `next_request_code` | device | |
| class_preparing_tracker | `scheduled_ids` | school | `ClassPreparingNotificationScheduler.kt` `cancelAllTrackedLocked` |
| class_preparing_tracker | `slot_request_codes` | school | `ClassPreparingNotificationScheduler.kt` `cancelAllTrackedLocked` |
| debug_clock | `frozen` | device | |
| debug_clock | `instant_millis` | device | |
| debug_clock | `saved_at_real_millis` | device | |
| debug_fixture | `demo_mode` | device | |
| debug_fixture | `demo_moodle_read` | device | |
| debug_fixture | `library_qr_content` | device | |
| debug_fixture | `library_qr_fake_signed_in` | device | |
| debug_fixture | `library_username` | device | |
| debug_fixture | `student_id` | device | |
| debug_outside_apps | `missing` | device | |
| moodle_files | `folder_salt` | device | |
| push_diagnostics | `has_token` | device | |
| push_diagnostics | `last_error` | device | |
| push_diagnostics | `registered` | device | |
| push_diagnostics | `server_push_user_opt_out` | device | |
| school_mail_dev_server | `domain` | device | |
| school_mail_dev_server | `enabled` | device | |
| school_mail_dev_server | `imap_host` | device | |
| school_mail_dev_server | `imap_port` | device | |
| school_mail_dev_server | `imap_security` | device | |
| school_mail_dev_server | `smtp_host` | device | |
| school_mail_dev_server | `smtp_port` | device | |
| school_mail_dev_server | `smtp_security` | device | |
| server_push | `seen_ids_json` | device | |
| server_push | `seen_ids` | device | |
| server_push_intent | `token` | device | |
| tigerduck_auth | `stored_password_refusals` | school | `StoredPasswordLatch.kt` `forget` |
| tigerduck_auth | `stored_password_refused_at` | school | `StoredPasswordLatch.kt` `forget` |
| tigerduck_auth | `stored_password_rejected_reason` | school | `StoredPasswordLatch.kt` `forget` |
| tigerduck_auth | `stored_password_rejected` | school | `StoredPasswordLatch.kt` `takeUpLatch` |
| tigerduck_browser_allowlist | `allow_every_site` | device | |
| tigerduck_browser_allowlist | `custom_entries` | device | |
| tigerduck_browser_cleanup | `pending` | device | |
| tigerduck_browser_cleanup | `request` | device | |
| tigerduck_browser_settings | `appearance` | device | |
| tigerduck_browser_settings | `desktop/*` | school | `BrowserSettings.kt` `clearSites` |
| tigerduck_browser_settings | `zoom/*` | school | `BrowserSettings.kt` `clearSites` |
| tigerduck_live_activity | `assignment_lead_sec` | device | |
| tigerduck_live_activity | `class_lead_sec` | device | |
| tigerduck_live_activity | `enabled` | device | |
| tigerduck_live_activity | `has_unconfirmed_sync_edit` | school | `NotificationSettingsSync.kt` `cancelPendingPushes` |
| tigerduck_live_activity | `show_assignment` | device | |
| tigerduck_live_activity | `show_class_preparing` | device | |
| tigerduck_live_activity | `show_in_class` | device | |
| tigerduck_live_activity | `show_on_lock_screen` | device | |
| tigerduck_live_activity | `sound_assignment` | device | |
| tigerduck_live_activity | `sound_class_preparing` | device | |
| tigerduck_live_activity | `sound_in_class` | device | |
| tigerduck_permissions | `chip_grant_reread` | device | |
| tigerduck_permissions | `declined_*` | device | |
| tigerduck_permissions | `granted_*` | device | |
| tigerduck_permissions | `muted_*` | device | |
| tls_pin_acceptances | `acceptances` | device | |
| tls_pin_acceptances | `cancelledAt.*` | device | |
| wear_bridge_state | `libraryCredentialEpoch` | device | |
| wear_bridge_state | `tlsPinsEpoch` | device | |

`moodle_files` holds the salt that names the downloaded files' folders, which the sign-out
deletes with DataCache's. `class_preparing_tracker`'s `next_request_code` stays so that no code an
alarm may still hold is handed out again. `stored_password_rejected` is the refusal flag of builds
before the count; the latch takes it up into `stored_password_refusals` when it opens. A site's
zoom and desktop choice go with the in-app browser's clearing (`BrowserDataCleaner.clear()`),
which `AccountSessionEffects` runs after each sign-out and sign-in.

## DataCache

Under `cacheDir/TigerDuckCache/`, `filesDir/TigerDuckData/` and, for the downloaded Moodle files,
`filesDir/moodle_files/`. A `*` stands for a term or a student ID.

| Store | Name | Scope | Cleared in |
|---|---|---|---|
| cache | `assignments.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `calendar_events.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `closed_assignments.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `course_custom_names.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `course_lookups.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `course_rows_seen.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `courses.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `courses_*.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `deleted_courses.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `hand_added_hides.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `ignored_assignments.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `ignored_courses.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `manual_courses_*.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `marked_completed_assignments.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `moodle/` | school | `DataCache.kt` `clearAllUserData` |
| cache | `moodle_assignments.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `moodle_course_ids.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `moodle_files/` | school | `DataCache.kt` `clearAllUserData` |
| cache | `moodle_open/` | school | `DataCache.kt` `clearAllUserData` |
| cache | `pending_course_adds.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `pending_course_deletes.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `pending_course_ignores.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `pending_course_overrides.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `restored_courses.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `score_*.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `selection_answered_at.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `selection_dropped.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `selection_rosters.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `semester_reset_at.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `server_dropped.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `server_known_courses.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `skipped_dates.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `unsent_marks.json` | school | `DataCache.kt` `clearAllUserData` |
| cache | `*.tmp` | school | `DataCache.kt` `clearAllUserData` |

`*.tmp` is what a write a process death cut short leaves beside the file it was to replace.

## Databases

| Store | Name | Scope | Cleared in |
|---|---|---|---|
| database | `mail_search.db` | mail | `MailAccount.kt` `leaveMailOf` via `searchIndex?.clearUnless` |
| database | `tigerduck.db` | device | |

`tigerduck.db` is the Room database builds before 1.1.6 shipped and nothing used;
`DataMigration` deletes it.
