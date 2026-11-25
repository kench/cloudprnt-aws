import {join} from 'node:path';

import {App} from 'aws-cdk-lib';
import {Code} from 'aws-cdk-lib/aws-lambda';

import {CloudPrntStack} from '../lib/cloudprnt_stack';

/** The account and region the tests synthesize for. */
export const ACCOUNT = '123456789012';
export const REGION = 'us-west-2';

/** A CloudPrnt stack with placeholder Lambda packages. */
export function testStack(): CloudPrntStack {
  const placeholder = Code.fromAsset(join(__dirname, 'fixtures', 'lambda'));
  return new CloudPrntStack(new App(), 'TestStack', {
    env: {account: ACCOUNT, region: REGION},
    printerApiCode: placeholder,
    jobApiCode: placeholder,
  });
}
