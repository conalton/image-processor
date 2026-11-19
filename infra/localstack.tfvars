aws_region        = "eu-north-1"
account_id        = "000000000000"
endpoint          = "http://localstack:4566"
s3_use_path_style = true
tag_resources     = false
# Chromium and Firefox resolve *.localhost to loopback themselves.
s3_public_endpoint = "http://s3.localhost:4566"
