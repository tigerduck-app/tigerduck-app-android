# Greptile Review Rules

## F-Droid metadata

- In `metadata/org.ntust.app.tigerduck.fdroid.yml`, ignore the `commit:` field when its value is the placeholder `<will be replaced by GitHub Action when creating release>`. Do not flag it as missing, invalid, or insecure.
  - **Why:** F-Droid requires the `commit:` field to be a full commit hash (plain-text tags like `v1.3.5` are not allowed). Because the release commit doesn't exist yet at PR time, the real hash is injected by a GitHub Action on the `main` branch when the release is cut. The placeholder is intentional.

## Account-ID field IME flip

- In `app/src/main/java/org/ntust/app/tigerduck/ui/component/OutlinedAccountIdField.kt`, the `computeAccountInputType` function flips the IME to `TYPE_CLASS_NUMBER` as soon as `value` is non-empty (i.e. immediately after the leading letter is typed). Do not flag this as a bug, do not suggest extending the text/`VISIBLE_PASSWORD` branch to cover `length == 1 && value[0].isLetter()` or any other "single leading letter" case, and do not flag that backspacing all digits down to just the letter leaves the user on the numeric pad with no way to delete the letter from the IME.
  - **Why:** This is intentional UX. Switching to the numeric pad the instant the first char is entered makes the digit-entry path one tap shorter — the common case. The "can't backspace-delete the lone letter from the numeric IME" trade-off is accepted: users who really want to retype the prefix can either tap the trailing `Cancel` icon to clear the field, or use the standard-keyboard compatibility toggle that the field already renders. A previous change tried to keep the text IME while `length == 1 && value[0].isLetter()` and was reverted because it delayed the numeric-pad switch by one keystroke on every account-ID entry, which is the hot path.
