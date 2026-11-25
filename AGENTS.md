# AGENTS.md

See `README.md` for the layout, build and deploy.

## Check your work

```sh
cd lambda && ./gradlew spotlessApply check lambdaZip
cd cdk && npm ci && npm run build && npm run lint && npm test && npx cdk synth
```

`cdk synth` needs the Lambda packages from `./gradlew lambdaZip`.

## Rules

- Change the API in `contract/model` first; `lambda/model` generates the Java types from it.
- Java: Google Java Style (Spotless, Checkstyle). TypeScript: gts, named exports, `snake_case`
  file names. At least 90% line coverage in both.
- IAM stays least-privilege, and the tests assert each policy exactly.
- Don't deploy unless asked.
- Conventional Commits for commits and PR titles (scopes `contract`, `lambda`, `cdk`, `ci`,
  `deps`).
