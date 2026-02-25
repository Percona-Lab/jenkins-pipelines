# Tests

Unit, integration, and e2e tests for the OpenShift cleanup Lambda.

## Running Tests

```bash
just test                 # Unit tests only
just test-coverage        # With coverage report
pytest -m openshift       # OpenShift-specific tests
pytest -m "not slow"      # Skip slow tests
```

## Key Fixtures

**`make_instance`** - Create test instances:
```python
make_instance(name="test", billing_tag="pmm-staging")
make_instance(ttl_expired=True, hours_old=3)
make_instance(protected=True)
make_instance(openshift=True, infra_id="my-infra-123")
```

**`time_utils`** - Time helpers:
```python
time_utils.hours_ago(3)
time_utils.days_ago(30)
time_utils.now()
```