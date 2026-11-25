/**
 * @fileoverview Where printers, jobs and job content live.
 */

import {Duration, RemovalPolicy} from 'aws-cdk-lib';
import {
  AttributeType,
  Billing,
  ProjectionType,
  TableClass,
  TableV2,
} from 'aws-cdk-lib/aws-dynamodb';
import {
  BlockPublicAccess,
  Bucket,
  BucketEncryption,
  BucketNamespace,
  ObjectOwnership,
} from 'aws-cdk-lib/aws-s3';
import {Construct} from 'constructs';

/** How long finished jobs and job content are kept, in days. */
export const RETENTION_DAYS = 30;

/** The jobs table's sparse index of pending jobs. */
export const QUEUE_INDEX = 'queue';

const TABLE_SETTINGS = {
  tableClass: TableClass.STANDARD,
  billing: Billing.onDemand(),
};

/** The printers and jobs tables and the job content bucket. */
export class Storage extends Construct {
  /** Registered printers, keyed by `mac`. */
  readonly printers: TableV2;

  /** Jobs, keyed by `mac` and `jobId`, with the `queue` index of pending jobs. */
  readonly jobs: TableV2;

  /** Job content, under `jobs/<mac>/<jobId>`. */
  readonly content: Bucket;

  constructor(scope: Construct, id: string) {
    super(scope, id);

    this.printers = new TableV2(this, 'Printers', {
      ...TABLE_SETTINGS,
      partitionKey: {name: 'mac', type: AttributeType.STRING},
      removalPolicy: RemovalPolicy.RETAIN,
    });

    this.jobs = new TableV2(this, 'Jobs', {
      ...TABLE_SETTINGS,
      partitionKey: {name: 'mac', type: AttributeType.STRING},
      sortKey: {name: 'jobId', type: AttributeType.STRING},
      // Pending jobs carry `queue` (their printer's MAC address), so the
      // oldest one is a single query.
      globalSecondaryIndexes: [
        {
          indexName: QUEUE_INDEX,
          partitionKey: {name: 'queue', type: AttributeType.STRING},
          sortKey: {name: 'jobId', type: AttributeType.STRING},
          projectionType: ProjectionType.ALL,
        },
      ],
      timeToLiveAttribute: 'expiresAt',
      removalPolicy: RemovalPolicy.RETAIN,
    });

    this.content = new Bucket(this, 'Content', {
      bucketNamePrefix: 'cloudprnt-jobs',
      bucketNamespace: BucketNamespace.ACCOUNT_REGIONAL,
      blockPublicAccess: BlockPublicAccess.BLOCK_ALL,
      objectOwnership: ObjectOwnership.BUCKET_OWNER_ENFORCED,
      encryption: BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      minimumTLSVersion: 1.2,
      lifecycleRules: [{expiration: Duration.days(RETENTION_DAYS)}],
      removalPolicy: RemovalPolicy.RETAIN,
    });
  }
}
