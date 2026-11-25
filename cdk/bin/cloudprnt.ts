/**
 * @fileoverview The CDK app entry point (cdk.json runs it with tsx). The
 * account and region come from the AWS CLI profile or the environment.
 */

import {existsSync} from 'node:fs';
import {join} from 'node:path';

import {App} from 'aws-cdk-lib';
import {Code} from 'aws-cdk-lib/aws-lambda';

import {CloudPrntStack} from '../lib/cloudprnt_stack';

/** A Lambda package built by `./gradlew lambdaZip` in ../lambda. */
function lambdaPackage(project: string): Code {
  const zip = join(
    __dirname,
    '..',
    '..',
    'lambda',
    project,
    'build',
    'lambda',
    `${project}.zip`,
  );
  if (!existsSync(zip)) {
    throw new Error(`${zip} not found: run ./gradlew lambdaZip in lambda/`);
  }
  return Code.fromAsset(zip);
}

const app = new App();
new CloudPrntStack(app, 'CloudPrnt', {
  env: {
    account: process.env.CDK_DEFAULT_ACCOUNT,
    region: process.env.CDK_DEFAULT_REGION,
  },
  printerApiCode: lambdaPackage('printer-api'),
  jobApiCode: lambdaPackage('job-api'),
});
