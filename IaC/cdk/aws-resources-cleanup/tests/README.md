# AWS Resource Cleanup - Test Suite

## 📊 Test Structure

This test suite follows the **Testing Pyramid** pattern for optimal test organization and execution speed:

```
tests/
├── conftest.py                    # Root fixtures & fixture factories
├── pytest.ini                     # Pytest configuration & markers
├── unit/                          # ⚡ Fast, isolated unit tests (69 tests)
│   ├── conftest.py               # Unit-specific fixtures
│   ├── test_protection_logic.py  # Protection detection rules
│   ├── test_billing_validation.py # Billing tag validation
│   ├── test_cluster_detection.py # Cluster name extraction
│   ├── test_tag_conversion.py    # Tag format conversion
│   ├── test_policy_priority.py   # Policy evaluation order
│   └── policies/                 # Cleanup policy tests
│       ├── test_ttl_policy.py
│       ├── test_stop_policy.py
│       ├── test_long_stopped_policy.py
│       └── test_untagged_policy.py
├── integration/                   # 🔗 Component interaction tests
│   ├── conftest.py               # Integration fixtures & mocks
│   └── (to be migrated)
└── e2e/                          # 🌐 Full workflow tests
    ├── conftest.py               # E2E fixtures
    └── (to be migrated)
```

## 🚀 Running Tests

### By Directory (Recommended)
```bash
# Fast unit tests only (< 1 second)
just test
# or
cd aws-resources-cleanup
PYTHONPATH=lambda:$PYTHONPATH uv run --with pytest pytest tests/unit/ -v

# Integration tests
pytest tests/integration/ -v

# End-to-end tests  
pytest tests/e2e/ -v

# All tests
pytest tests/
```

### By Marker
```bash
# Run only unit tests
pytest -m unit

# Run policy-specific tests
pytest -m policies

# Run AWS-related tests
pytest -m "unit and aws"

# Run OpenShift tests
pytest -m openshift

# Skip slow tests
pytest -m "not slow"

# Run smoke tests only
pytest -m smoke
```

### With Coverage
```bash
just test-coverage
# or
pytest --cov=aws_resource_cleanup --cov-report=html
open htmlcov/index.html
```

## 📝 Writing Tests

### Using Fixture Factories

#### `make_instance` - Flexible Test Data Creation

The `make_instance` fixture factory replaces 8+ individual fixtures with a single, flexible function:

```python
def test_something(make_instance):
    # Simple instance
    instance = make_instance(name="test", billing_tag="pmm-staging")
    
    # Instance with expired TTL
    instance = make_instance(
        ttl_expired=True, 
        hours_old=3, 
        ttl_hours=1
    )
    
    # Protected instance
    instance = make_instance(protected=True)
    
    # OpenShift cluster instance
    instance = make_instance(
        openshift=True, 
        infra_id="my-infra-123",
        cluster_name="my-cluster"
    )
    
    # EKS cluster instance
    instance = make_instance(
        eks=True,
        eks_cluster="my-eks-cluster"
    )
    
    # Custom tags
    instance = make_instance(
        billing_tag="pmm-staging",
        owner="test-user",
        **{"custom-tag": "custom-value"}
    )
```

**Parameters:**
- `name`: Instance name (default: "test-instance")
- `state`: Instance state (default: "running")
- `billing_tag`: Billing tag value
- `ttl_expired`: Whether TTL should be expired (default: False)
- `ttl_hours`: TTL duration in hours (default: 1)
- `hours_old`: How many hours ago instance was launched
- `days_old`: How many days ago instance was launched
- `protected`: Use protected billing tag (default: False)
- `openshift`: Add OpenShift tags (default: False)
- `eks`: Add EKS tags (default: False)
- `owner`: Owner tag
- `cluster_name`: Cluster name tag
- `stop_after_days`: Add stop-after-days tag
- `**kwargs`: Additional custom tags

#### `time_utils` - Consistent Time Handling

```python
def test_time_based(time_utils):
    # Get times relative to current_time
    three_hours_ago = time_utils.hours_ago(3)
    thirty_days_ago = time_utils.days_ago(30)
    twenty_minutes_ago = time_utils.seconds_ago(1200)
    
    # Get timestamps
    ts = time_utils.timestamp()
    old_ts = time_utils.timestamp(time_utils.days_ago(5))
    
    # Get current time
    now = time_utils.now()
```

### Test Organization Best Practices

#### 1. Use Descriptive Test Names
```python
def test_instance_with_expired_ttl_creates_terminate_action():
    """Clear, descriptive name following pattern:
    test_<what>_<condition>_<expected_result>
    """
```

#### 2. Follow GIVEN-WHEN-THEN Pattern
```python
def test_protection_logic(make_instance):
    """
    GIVEN an instance with a persistent billing tag
    WHEN is_protected is called
    THEN True should be returned (instance is protected)
    """
    # Arrange
    instance = make_instance(protected=True)
    
    # Act
    result = is_protected(tags_dict)
    
    # Assert
    assert result is True
```

#### 3. Group Related Tests in Classes
```python
@pytest.mark.unit
@pytest.mark.policies
class TestTTLExpirationDetection:
    """Test TTL expiration detection logic."""
    
    def test_expired_ttl_creates_action(self):
        # ...
    
    def test_valid_ttl_returns_none(self):
        # ...
```

#### 4. Use Markers Appropriately
```python
@pytest.mark.unit           # Automatically added by unit/conftest.py
@pytest.mark.policies       # Indicates policy-related test
@pytest.mark.aws            # Test involves AWS concepts
@pytest.mark.openshift      # OpenShift-specific test
@pytest.mark.slow           # Slow-running test (>1s)
class TestMyFeature:
    # ...
```

## 🏷️ Available Test Markers

| Marker | Description | Usage |
|--------|-------------|-------|
| `unit` | Fast, isolated unit tests | Auto-applied to tests/unit/ |
| `integration` | Component interaction tests | Auto-applied to tests/integration/ |
| `e2e` | End-to-end workflow tests | Auto-applied to tests/e2e/ |
| `aws` | Tests involving AWS services | Manual |
| `policies` | Cleanup policy tests | Manual |
| `openshift` | OpenShift-specific tests | Manual |
| `eks` | EKS-specific tests | Manual |
| `slow` | Slow tests (>1s) | Manual |
| `smoke` | Critical path smoke tests | Manual |

## 📚 Test Categories

### Unit Tests (`tests/unit/`)
**Purpose:** Test individual functions and business logic in isolation
**Speed:** < 1 second for all tests
**Mocking:** Minimal to none (pure business logic)

**What to test:**
- Protection detection rules
- Billing tag validation
- Cluster name extraction
- Policy priority logic
- Tag conversion utilities
- Individual policy functions

**Example:**
```python
def test_persistent_tag_is_protected(make_instance):
    instance = make_instance(billing_tag="jenkins-cloud")
    assert is_protected(tags_dict) is True
```

### Integration Tests (`tests/integration/`)
**Purpose:** Test component interactions with mocking
**Speed:** 1-5 seconds
**Mocking:** AWS services (EC2, SNS, CloudFormation)

**What to test:**
- Action execution with AWS mocks
- Region cleanup orchestration
- Notification flow
- Error handling in execution layer

**Example:**
```python
@patch("aws_resource_cleanup.ec2.instances.boto3.client")
def test_terminate_action_execution(mock_boto):
    # Test with mocked AWS service
```

### End-to-End Tests (`tests/e2e/`)
**Purpose:** Test complete workflows
**Speed:** 5-10 seconds
**Mocking:** Comprehensive AWS environment

**What to test:**
- Lambda handler entry point
- Multi-region orchestration
- Complete execution flows
- Error propagation

## 🔧 Troubleshooting

### Tests Not Found
```bash
# Ensure PYTHONPATH includes lambda directory
cd aws-resources-cleanup
PYTHONPATH=lambda:$PYTHONPATH pytest tests/
```

### Import Errors
```bash
# Install test dependencies
uv pip install -r requirements.txt
cd lambda && uv pip install -r aws_resource_cleanup/requirements.txt
```

### Fixture Not Found
- Check if fixture is in the correct conftest.py
- Remember fixture scope (function, class, module, session)
- Verify fixture is imported or defined in parent conftest.py

## 📈 Test Statistics

**Current Status:**
- ✅ 69 unit tests passing (100%)
- ⏳ Integration tests: to be migrated
- ⏳ E2E tests: to be migrated

**Unit Test Breakdown:**
- Protection Logic: 17 tests
- Billing Validation: 11 tests  
- Cluster Detection: 5 tests
- Tag Conversion: 3 tests
- Policy Priority: 4 tests
- TTL Policy: 14 tests
- Stop Policy: 5 tests
- Long Stopped Policy: 4 tests
- Untagged Policy: 6 tests

## 🎯 Migration Notes

### Legacy Fixtures (Deprecated)
The following fixtures are kept for backward compatibility but should not be used in new tests:
- `instance_with_valid_billing_tag` → use `make_instance(billing_tag="pmm-staging")`
- `instance_with_expired_ttl` → use `make_instance(ttl_expired=True, hours_old=2)`
- `instance_without_billing_tag` → use `make_instance()` (no billing_tag)
- `instance_stopped_long_term` → use `make_instance(state="stopped", days_old=35)`
- `protected_instance` → use `make_instance(protected=True)`
- `openshift_cluster_instance` → use `make_instance(openshift=True)`
- `eks_cluster_instance` → use `make_instance(eks=True)`

## 🚦 CI/CD Integration

```bash
# Full CI pipeline
just ci

# Or manually
just lint
just test
just synth
```

## 📖 Additional Resources

- [Pytest Documentation](https://docs.pytest.org/)
- [Testing Best Practices](https://pytest-with-eric.com/pytest-best-practices/pytest-organize-tests/)
- [Test Pyramid Concept](https://martinfowler.com/articles/practical-test-pyramid.html)