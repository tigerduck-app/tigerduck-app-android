# Greptile Review Rules

## F-Droid metadata

- In `metadata/org.ntust.app.tigerduck.fdroid.yml`, ignore the `commit:` field when its value is the placeholder `<will be replaced by GitHub Action when creating release>`. Do not flag it as missing, invalid, or insecure.
  - **Why:** F-Droid requires the `commit:` field to be a full commit hash (plain-text tags like `v1.3.5` are not allowed). Because the release commit doesn't exist yet at PR time, the real hash is injected by a GitHub Action on the `main` branch when the release is cut. The placeholder is intentional.
