# Release Signing For Public Reviewers

Debug builds require no private signing configuration:

```bash
./gradlew :app:assembleDebug
```

Release packaging intentionally fails unless the repository owner supplies a private keystore outside the repository. Configure either these `local.properties` keys:

```properties
habit.release.storeFile=/absolute/path/to/private-keystore.jks
habit.release.storePassword=...
habit.release.keyAlias=...
habit.release.keyPassword=...
```

or the equivalent environment variables:

```bash
HABIT_RELEASE_STORE_FILE=/absolute/path/to/private-keystore.jks
HABIT_RELEASE_STORE_PASSWORD=...
HABIT_RELEASE_KEY_ALIAS=...
HABIT_RELEASE_KEY_PASSWORD=...
```

Never commit a keystore, `local.properties`, passwords, aliases tied to private infrastructure, or generated signing evidence. Redesign contributors should use debug builds. The owner-signed APK attached to the public GitHub release is the installation reference for non-developers.
