/**
 * @fileoverview The CloudPRNT backend: storage, the two Lambdas and the HTTP
 * API.
 */

import {CfnOutput, Stack} from 'aws-cdk-lib';
import type {StackProps} from 'aws-cdk-lib';
import type {Code} from 'aws-cdk-lib/aws-lambda';
import type {Construct} from 'constructs';

import {Service} from './service';
import {Storage} from './storage';

/** Settings for {@link CloudPrntStack}. */
export interface CloudPrntStackProps extends StackProps {
  /** The printer endpoint's deployment package. */
  readonly printerApiCode: Code;
  /** The job API's deployment package. */
  readonly jobApiCode: Code;
}

/** Everything the CloudPRNT backend needs. */
export class CloudPrntStack extends Stack {
  /** The tables and bucket. */
  readonly storage: Storage;

  /** The Lambdas and the HTTP API. */
  readonly service: Service;

  constructor(scope: Construct, id: string, props: CloudPrntStackProps) {
    super(scope, id, {
      description: 'Star CloudPRNT backend',
      ...props,
    });

    this.storage = new Storage(this, 'Storage');
    this.service = new Service(this, 'Service', {
      storage: this.storage,
      printerApiCode: props.printerApiCode,
      jobApiCode: props.jobApiCode,
    });

    new CfnOutput(this, 'CloudPrntUrl', {
      description: "The printers' CloudPRNT server URL",
      value: this.service.cloudPrntUrl,
    });
    new CfnOutput(this, 'ApiUrl', {
      description: 'The base URL of the print-job API (/v1/printers)',
      value: this.service.api.apiEndpoint,
    });
    new CfnOutput(this, 'JobApiPolicyArn', {
      description: 'Attach to roles that call the print-job API',
      value: this.service.jobApiPolicy.managedPolicyArn,
    });
  }
}
