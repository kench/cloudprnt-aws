# cloudprnt-aws

A serverless backend for [Star Micronics CloudPRNT](https://star-m.jp/products/s_print/sdk/StarCloudPRNT/manual/en/index.html)
receipt printers on AWS: API Gateway (HTTP API), Lambda (Java 25), DynamoDB and S3, deployed
with the AWS CDK. Not affiliated with Star Micronics.

- **Printers** poll `POST /cloudprnt`, fetch jobs with `GET` and confirm them with `DELETE`,
  using HTTP Basic auth.
- **Applications** register printers and submit jobs through a SigV4-signed REST API under
  `/v1/printers`.

The API is defined in Smithy in [`contract/`](contract/model).

## Layout

```
contract/   Smithy model: the CloudPRNT protocol and the print-job API
lambda/     Java Lambdas (Gradle): model, common, printer-api, job-api
cdk/        CDK app (TypeScript)
```

## Build

Requires JDK 25 and Node.js 26.

```sh
cd lambda && ./gradlew check lambdaZip spdxSbom
cd cdk && npm ci && npm run build && npm run lint && npm test && npx cdk synth
```

## Deploy

```sh
cd cdk && npx cdk deploy CloudPrnt
```

Or run the **Deploy** workflow, which assumes the role in the repository variable
`AWS_ROLE_ARN` (via GitHub OIDC) in `AWS_REGION`.

Stack outputs:

| Output | Use |
| --- | --- |
| `CloudPrntUrl` | The printer's CloudPRNT server URL |
| `ApiUrl` | Base URL of the print-job API |
| `JobApiPolicyArn` | Attach to roles that call the print-job API |

## Use

1. Register the printer. Keep the password; it is shown only once.

   ```sh
   awscurl --service execute-api -X POST "$API_URL/v1/printers" \
     -d '{"mac": "00:11:62:12:34:56", "name": "Front counter"}'
   ```

2. In the printer's CloudPRNT settings, set the server URL to `CloudPrntUrl`, the user name to
   the MAC address (`001162123456` or `00:11:62:12:34:56`) and the password to the one issued.

3. Submit a job. `content` is base64, up to 4 MiB.

   ```sh
   awscurl --service execute-api -X POST "$API_URL/v1/printers/00:11:62:12:34:56/jobs" \
     -d '{"mediaType": "text/plain", "content": "SGVsbG8sIHdvcmxkIQo="}'
   ```

Jobs are served in the media type they were submitted in, so submit a format the printer
accepts, such as `application/vnd.star.starprnt`, `image/png` or `text/plain`. Finished jobs
and job content are kept for 30 days.

## SBOM

SPDX 2.3 SBOMs are built by CI (`sbom-*` artifacts) and attached to each release:
`printer-api.spdx.json` and `job-api.spdx.json` for the Lambda packages, `cdk.spdx.json` for the
CDK app. Locally: `./gradlew spdxSbom` in `lambda/`, `npm run sbom` in `cdk/`.

## License

[CC0 1.0](LICENSE)
