import {Match, Template} from 'aws-cdk-lib/assertions';

import {QUEUE_INDEX, RETENTION_DAYS} from '../lib/storage';
import {
  JOB_API_HANDLER,
  JOB_API_ROUTES,
  PRINTER_API_HANDLER,
  THROTTLE_BURST,
  THROTTLE_RATE_PER_SECOND,
} from '../lib/service';
import {testStack} from './test_stack';

const template = Template.fromStack(testStack());

/** The logical ID of the only resource of `type` whose ID starts with `prefix`. */
function logicalId(type: string, prefix: string): string {
  const ids = Object.keys(template.findResources(type)).filter(id =>
    id.startsWith(prefix),
  );
  expect(ids).toHaveLength(1);
  return ids[0];
}

const printersTable = logicalId(
  'AWS::DynamoDB::GlobalTable',
  'StoragePrinters',
);
const jobsTable = logicalId('AWS::DynamoDB::GlobalTable', 'StorageJobs');
const bucket = logicalId('AWS::S3::Bucket', 'StorageContent');
const arn = (id: string) => ({'Fn::GetAtt': [id, 'Arn']});
const jobObjects = {'Fn::Join': ['', [arn(bucket), '/jobs/*']]};

/** The statements of the policy attached to the role whose ID starts with `prefix`. */
function statementsOf(prefix: string): unknown {
  const role = logicalId('AWS::IAM::Role', prefix);
  const policies = Object.values(
    template.findResources('AWS::IAM::Policy', {
      Properties: {Roles: [{Ref: role}]},
    }),
  );
  expect(policies).toHaveLength(1);
  return policies[0].Properties.PolicyDocument.Statement;
}

describe('storage', () => {
  test('the printers table is keyed by MAC address and retained', () => {
    template.hasResource('AWS::DynamoDB::GlobalTable', {
      Properties: {
        KeySchema: [{AttributeName: 'mac', KeyType: 'HASH'}],
        BillingMode: 'PAY_PER_REQUEST',
        Replicas: [Match.objectLike({TableClass: 'STANDARD'})],
      },
      DeletionPolicy: 'Retain',
    });
  });

  test('the jobs table has the queue index and a TTL', () => {
    template.hasResource('AWS::DynamoDB::GlobalTable', {
      Properties: {
        KeySchema: [
          {AttributeName: 'mac', KeyType: 'HASH'},
          {AttributeName: 'jobId', KeyType: 'RANGE'},
        ],
        GlobalSecondaryIndexes: [
          {
            IndexName: QUEUE_INDEX,
            KeySchema: [
              {AttributeName: 'queue', KeyType: 'HASH'},
              {AttributeName: 'jobId', KeyType: 'RANGE'},
            ],
            Projection: {ProjectionType: 'ALL'},
          },
        ],
        TimeToLiveSpecification: {AttributeName: 'expiresAt', Enabled: true},
      },
      DeletionPolicy: 'Retain',
    });
  });

  test('job content is private, encrypted and expires', () => {
    template.hasResource('AWS::S3::Bucket', {
      Properties: {
        BucketNamePrefix: 'cloudprnt-jobs',
        BucketNamespace: 'account-regional',
        PublicAccessBlockConfiguration: {
          BlockPublicAcls: true,
          BlockPublicPolicy: true,
          IgnorePublicAcls: true,
          RestrictPublicBuckets: true,
        },
        BucketEncryption: {
          ServerSideEncryptionConfiguration: [
            {ServerSideEncryptionByDefault: {SSEAlgorithm: 'AES256'}},
          ],
        },
        LifecycleConfiguration: {
          Rules: [{ExpirationInDays: RETENTION_DAYS, Status: 'Enabled'}],
        },
      },
      DeletionPolicy: 'Retain',
    });
  });
});

describe('lambdas', () => {
  test.each([
    ['ServicePrinterApi', PRINTER_API_HANDLER, 10],
    ['ServiceJobApi', JOB_API_HANDLER, 30],
  ])('%s runs on Java 25 ARM64', (prefix, handler, timeout) => {
    const fn = logicalId('AWS::Lambda::Function', prefix);
    expect(
      template.findResources('AWS::Lambda::Function')[fn].Properties,
    ).toMatchObject({
      Runtime: 'java25',
      Architectures: ['arm64'],
      Handler: handler,
      MemorySize: 1024,
      Timeout: timeout,
      Environment: {
        Variables: {
          PRINTERS_TABLE: {Ref: printersTable},
          JOBS_TABLE: {Ref: jobsTable},
          CONTENT_BUCKET: {Ref: bucket},
        },
      },
    });
  });

  test('logs are kept for 30 days', () => {
    template.resourceCountIs('AWS::Logs::LogGroup', 2);
    template.allResourcesProperties('AWS::Logs::LogGroup', {
      RetentionInDays: 30,
    });
  });

  test('the printer endpoint can only read printers, run jobs and read content', () => {
    expect(statementsOf('ServicePrinterApiServiceRole')).toEqual([
      {
        Action: ['dynamodb:GetItem', 'dynamodb:UpdateItem'],
        Effect: 'Allow',
        Resource: arn(printersTable),
      },
      {
        Action: ['dynamodb:GetItem', 'dynamodb:UpdateItem', 'dynamodb:Query'],
        Effect: 'Allow',
        Resource: [
          arn(jobsTable),
          {'Fn::Join': ['', [arn(jobsTable), `/index/${QUEUE_INDEX}`]]},
        ],
      },
      {Action: 's3:GetObject', Effect: 'Allow', Resource: jobObjects},
      {
        Action: 's3:ListBucket',
        Condition: {StringLike: {'s3:prefix': 'jobs/*'}},
        Effect: 'Allow',
        Resource: arn(bucket),
      },
    ]);
  });

  test('the job API can manage printers and jobs and write content', () => {
    expect(statementsOf('ServiceJobApiServiceRole')).toEqual([
      {
        Action: [
          'dynamodb:GetItem',
          'dynamodb:PutItem',
          'dynamodb:UpdateItem',
          'dynamodb:DeleteItem',
          'dynamodb:Scan',
        ],
        Effect: 'Allow',
        Resource: arn(printersTable),
      },
      {
        Action: [
          'dynamodb:GetItem',
          'dynamodb:PutItem',
          'dynamodb:UpdateItem',
          'dynamodb:Query',
        ],
        Effect: 'Allow',
        Resource: arn(jobsTable),
      },
      {Action: 's3:PutObject', Effect: 'Allow', Resource: jobObjects},
    ]);
  });
});

describe('http api', () => {
  test('is dual-stack with a throttled default stage', () => {
    template.hasResourceProperties('AWS::ApiGatewayV2::Api', {
      ProtocolType: 'HTTP',
      IpAddressType: 'dualstack',
    });
    template.resourceCountIs('AWS::ApiGatewayV2::Stage', 1);
    template.hasResourceProperties('AWS::ApiGatewayV2::Stage', {
      StageName: '$default',
      AutoDeploy: true,
      DefaultRouteSettings: {
        ThrottlingRateLimit: THROTTLE_RATE_PER_SECOND,
        ThrottlingBurstLimit: THROTTLE_BURST,
      },
    });
  });

  test('printers reach /cloudprnt without IAM; the Lambda checks Basic auth', () => {
    for (const method of ['POST', 'GET', 'DELETE']) {
      template.hasResourceProperties('AWS::ApiGatewayV2::Route', {
        RouteKey: `${method} /cloudprnt`,
        AuthorizationType: 'NONE',
      });
    }
  });

  test('every job API route needs SigV4 and nothing else is routed', () => {
    for (const [method, path] of JOB_API_ROUTES) {
      template.hasResourceProperties('AWS::ApiGatewayV2::Route', {
        RouteKey: `${method} ${path}`,
        AuthorizationType: 'AWS_IAM',
      });
    }
    template.resourceCountIs(
      'AWS::ApiGatewayV2::Route',
      3 + JOB_API_ROUTES.length,
    );
  });

  test('the job API policy allows only the job routes', () => {
    template.hasResourceProperties('AWS::IAM::ManagedPolicy', {
      PolicyDocument: {
        Statement: [
          {
            Action: 'execute-api:Invoke',
            Effect: 'Allow',
            Resource: {
              'Fn::Join': [
                '',
                [
                  'arn:',
                  {Ref: 'AWS::Partition'},
                  ':execute-api:us-west-2:123456789012:',
                  {Ref: logicalId('AWS::ApiGatewayV2::Api', 'ServiceApi')},
                  '/$default/*/v1/printers*',
                ],
              ],
            },
          },
        ],
      },
    });
  });

  test('outputs the URLs and the policy', () => {
    const api = logicalId('AWS::ApiGatewayV2::Api', 'ServiceApi');
    template.hasOutput('CloudPrntUrl', {
      Value: {
        'Fn::Join': ['', [{'Fn::GetAtt': [api, 'ApiEndpoint']}, '/cloudprnt']],
      },
    });
    template.hasOutput('ApiUrl', {
      Value: {'Fn::GetAtt': [api, 'ApiEndpoint']},
    });
    template.hasOutput('JobApiPolicyArn', {
      Value: {Ref: logicalId('AWS::IAM::ManagedPolicy', 'ServiceJobApiPolicy')},
    });
  });
});
