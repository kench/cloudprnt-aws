/**
 * @fileoverview The two Lambdas behind one HTTP API: the CloudPRNT endpoint
 * that printers poll, and the print-job API for applications.
 */

import {Duration, Stack} from 'aws-cdk-lib';
import {
  HttpApi,
  HttpMethod,
  HttpStage,
  IpAddressType,
} from 'aws-cdk-lib/aws-apigatewayv2';
import {HttpIamAuthorizer} from 'aws-cdk-lib/aws-apigatewayv2-authorizers';
import {HttpLambdaIntegration} from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import {ManagedPolicy, PolicyStatement} from 'aws-cdk-lib/aws-iam';
import {Architecture, Function, Runtime} from 'aws-cdk-lib/aws-lambda';
import type {Code} from 'aws-cdk-lib/aws-lambda';
import {LogGroup, RetentionDays} from 'aws-cdk-lib/aws-logs';
import {Construct} from 'constructs';

import {QUEUE_INDEX} from './storage';
import type {Storage} from './storage';

/** The printer endpoint's Lambda handler. */
export const PRINTER_API_HANDLER =
  'tv.kenley.cloudprnt.printer.PrinterApiHandler::handleRequest';

/** The job API's Lambda handler. */
export const JOB_API_HANDLER =
  'tv.kenley.cloudprnt.jobs.JobApiHandler::handleRequest';

/** The path printers poll. */
export const CLOUDPRNT_PATH = '/cloudprnt';

/** Stage throttling: enough for a few hundred printers polling every 5 s. */
export const THROTTLE_RATE_PER_SECOND = 100;
export const THROTTLE_BURST = 200;

/** The job API's routes, all IAM-authorized. */
export const JOB_API_ROUTES: ReadonlyArray<[HttpMethod, string]> = [
  [HttpMethod.POST, '/v1/printers'],
  [HttpMethod.GET, '/v1/printers'],
  [HttpMethod.GET, '/v1/printers/{mac}'],
  [HttpMethod.DELETE, '/v1/printers/{mac}'],
  [HttpMethod.POST, '/v1/printers/{mac}/password'],
  [HttpMethod.POST, '/v1/printers/{mac}/jobs'],
  [HttpMethod.GET, '/v1/printers/{mac}/jobs'],
  [HttpMethod.GET, '/v1/printers/{mac}/jobs/{jobId}'],
  [HttpMethod.POST, '/v1/printers/{mac}/jobs/{jobId}/cancel'],
];

/** Settings for {@link Service}. */
export interface ServiceProps {
  /** The tables and bucket. */
  readonly storage: Storage;
  /** The printer endpoint's deployment package. */
  readonly printerApiCode: Code;
  /** The job API's deployment package. */
  readonly jobApiCode: Code;
}

/** The Lambdas, the HTTP API and the policy for calling the job API. */
export class Service extends Construct {
  /** Handles `/cloudprnt`. */
  readonly printerApi: Function;

  /** Handles `/v1/printers…`. */
  readonly jobApi: Function;

  /** The HTTP API. */
  readonly api: HttpApi;

  /** Allows calling every job API route; attach it to your applications' roles. */
  readonly jobApiPolicy: ManagedPolicy;

  constructor(scope: Construct, id: string, props: ServiceProps) {
    super(scope, id);
    const {printers, jobs, content} = props.storage;
    const environment = {
      PRINTERS_TABLE: printers.tableName,
      JOBS_TABLE: jobs.tableName,
      CONTENT_BUCKET: content.bucketName,
      // Faster cold starts for short request handlers.
      JAVA_TOOL_OPTIONS: '-XX:+TieredCompilation -XX:TieredStopAtLevel=1',
    };

    this.printerApi = new Function(this, 'PrinterApi', {
      description: 'CloudPRNT endpoint (poll, fetch, confirm)',
      runtime: Runtime.JAVA_25,
      architecture: Architecture.ARM_64,
      handler: PRINTER_API_HANDLER,
      code: props.printerApiCode,
      memorySize: 1024,
      timeout: Duration.seconds(10),
      logGroup: new LogGroup(this, 'PrinterApiLogs', {
        retention: RetentionDays.ONE_MONTH,
      }),
      environment,
    });

    this.jobApi = new Function(this, 'JobApi', {
      description: 'CloudPRNT print-job API',
      runtime: Runtime.JAVA_25,
      architecture: Architecture.ARM_64,
      handler: JOB_API_HANDLER,
      code: props.jobApiCode,
      memorySize: 1024,
      timeout: Duration.seconds(30),
      logGroup: new LogGroup(this, 'JobApiLogs', {
        retention: RetentionDays.ONE_MONTH,
      }),
      environment,
    });

    const jobsKeys = content.arnForObjects('jobs/*');
    // The printer endpoint reads printers and records polls; reads, fetches
    // and finishes jobs; and reads job content. ListBucket makes a missing
    // object a 404 rather than a 403.
    this.printerApi.addToRolePolicy(
      new PolicyStatement({
        actions: ['dynamodb:GetItem', 'dynamodb:UpdateItem'],
        resources: [printers.tableArn],
      }),
    );
    this.printerApi.addToRolePolicy(
      new PolicyStatement({
        actions: ['dynamodb:GetItem', 'dynamodb:UpdateItem', 'dynamodb:Query'],
        resources: [jobs.tableArn, `${jobs.tableArn}/index/${QUEUE_INDEX}`],
      }),
    );
    this.printerApi.addToRolePolicy(
      new PolicyStatement({actions: ['s3:GetObject'], resources: [jobsKeys]}),
    );
    this.printerApi.addToRolePolicy(
      new PolicyStatement({
        actions: ['s3:ListBucket'],
        resources: [content.bucketArn],
        conditions: {StringLike: {'s3:prefix': 'jobs/*'}},
      }),
    );

    // The job API manages printers, and creates, reads, lists and cancels
    // jobs. It never reads job content.
    this.jobApi.addToRolePolicy(
      new PolicyStatement({
        actions: [
          'dynamodb:GetItem',
          'dynamodb:PutItem',
          'dynamodb:UpdateItem',
          'dynamodb:DeleteItem',
          'dynamodb:Scan',
        ],
        resources: [printers.tableArn],
      }),
    );
    this.jobApi.addToRolePolicy(
      new PolicyStatement({
        actions: [
          'dynamodb:GetItem',
          'dynamodb:PutItem',
          'dynamodb:UpdateItem',
          'dynamodb:Query',
        ],
        resources: [jobs.tableArn],
      }),
    );
    this.jobApi.addToRolePolicy(
      new PolicyStatement({actions: ['s3:PutObject'], resources: [jobsKeys]}),
    );

    this.api = new HttpApi(this, 'Api', {
      apiName: 'cloudprnt',
      description: 'CloudPRNT endpoint and print-job API',
      ipAddressType: IpAddressType.DUAL_STACK,
      createDefaultStage: false,
    });
    new HttpStage(this, 'Stage', {
      httpApi: this.api,
      autoDeploy: true,
      throttle: {
        rateLimit: THROTTLE_RATE_PER_SECOND,
        burstLimit: THROTTLE_BURST,
      },
    });

    // Printers authenticate with HTTP Basic, which the Lambda checks.
    this.api.addRoutes({
      path: CLOUDPRNT_PATH,
      methods: [HttpMethod.POST, HttpMethod.GET, HttpMethod.DELETE],
      integration: new HttpLambdaIntegration(
        'PrinterIntegration',
        this.printerApi,
      ),
    });

    // Applications sign job API requests with SigV4.
    const jobIntegration = new HttpLambdaIntegration(
      'JobIntegration',
      this.jobApi,
    );
    const iam = new HttpIamAuthorizer();
    for (const [method, path] of JOB_API_ROUTES) {
      this.api.addRoutes({
        path,
        methods: [method],
        integration: jobIntegration,
        authorizer: iam,
      });
    }

    this.jobApiPolicy = new ManagedPolicy(this, 'JobApiPolicy', {
      description: 'Call the CloudPRNT print-job API',
      statements: [
        new PolicyStatement({
          actions: ['execute-api:Invoke'],
          resources: [
            Stack.of(this).formatArn({
              service: 'execute-api',
              resource: this.api.apiId,
              resourceName: '$default/*/v1/printers*',
            }),
          ],
        }),
      ],
    });
  }

  /** The printer endpoint's URL. */
  get cloudPrntUrl(): string {
    return `${this.api.apiEndpoint}${CLOUDPRNT_PATH}`;
  }
}
