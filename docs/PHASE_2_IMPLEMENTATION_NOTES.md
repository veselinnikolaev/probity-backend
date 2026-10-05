# Phase 2: Synchronous Domain Events Implementation

## Overview
This update implements synchronous domain events as specified in the Phase 2 design note. The implementation adds event publishing capabilities to portfolio and simulation services, with proper error handling and comprehensive test coverage.

## Changes Made

### 1. Domain Event Infrastructure

#### New Files Created:
- `src/main/java/me/veselin/probity/common/domain/event/DomainEvent.java`
  - Marker interface for all domain events
  - Extends Spring's ApplicationEvent for compatibility with Spring's event system

- `src/main/java/me/veselin/probity/common/domain/event/PortfolioUpdatedEvent.java`
  - Record representing portfolio mutation events
  - Fields: portfolioId (UUID), userId (UUID), occurredAt (Instant)

- `src/main/java/me/veselin/probity/common/domain/event/SimulationRequestedEvent.java`
  - Record representing simulation start events
  - Fields: portfolioId (UUID), userId (UUID), numberOfSimulations (int), timeHorizonDays (int), confidenceLevel (double), occurredAt (Instant)

- `src/main/java/me/veselin/probity/common/domain/event/SimulationCompletedEvent.java`
  - Record representing successful simulation completion
  - Fields: portfolioId (UUID), userId (UUID), simulationId (UUID), currentPortfolioValue (BigDecimal), occurredAt (Instant)

- `src/main/java/me/veselin/probity/common/domain/event/SimulationFailedEvent.java`
  - Record representing simulation failure
  - Fields: portfolioId (UUID), userId (UUID), errorMessage (String), occurredAt (Instant)

- `src/main/java/me/veselin/probity/common/domain/event/DomainEventPublisher.java`
  - Interface for publishing domain events
  - Single method: `void publish(DomainEvent event)`

- `src/main/java/me/veselin/probity/common/domain/event/SynchronousDomainEventPublisher.java`
  - Implementation of DomainEventPublisher
  - Delegates to Spring's ApplicationEventPublisher
  - Ensures synchronous execution

- `src/main/java/me/veselin/probity/common/config/EventConfiguration.java`
  - Spring configuration class
  - Defines custom `SimpleApplicationEventMulticaster` bean named "applicationEventMulticaster"
  - Configures error handler to log listener exceptions without failing the entire event processing
  - **Critical**: This ensures per-listener exception isolation

- `src/main/java/me/veselin/probity/simulation/event/NoOpSimulationEventListener.java`
  - Example listener demonstrating @EventListener usage
  - Listens to all simulation events but takes no action
  - Serves as a template for future event consumers

### 2. Service Layer Modifications

#### Modified Files:
- `src/main/java/me/veselin/probity/portfolio/service/portfolio/PortfolioCommandService.java`
  - Added `@Autowired DomainEventPublisher domainEventPublisher` field
  - Added event publish calls as the last statement before return in:
    - `create()` - publishes PortfolioUpdatedEvent after portfolio creation
    - `addPosition()` - publishes PortfolioUpdatedEvent after position addition
    - `updatePosition()` - publishes PortfolioUpdatedEvent after position update
    - `deletePosition()` - publishes PortfolioUpdatedEvent after position deletion
    - `update()` - publishes PortfolioUpdatedEvent after portfolio metadata update
    - `delete()` - publishes PortfolioUpdatedEvent after portfolio deletion
  - **Important**: All publish calls occur after successful persistence and are the final statement before return, ensuring no post-publish code can throw exceptions

- `src/main/java/me/veselin/probity/simulation/service/MonteCarloSimulationService.java`
  - Added `@Autowired DomainEventPublisher domainEventPublisher` field
  - Modified `run()` method to:
    - Publish `SimulationRequestedEvent` at the start of the method
    - Publish `SimulationCompletedEvent` after successful simulation and persistence
    - Publish `SimulationFailedEvent` in catch block before rethrowing the exception
  - **Critical**: The failure event is published before the exception is rethrown, ensuring listeners are notified even when the operation fails

### 3. Test Infrastructure

#### New Test Files Created:
- `src/test/java/me/veselin/probity/common/domain/event/EventPublishingIntegrationTest.java`
  - Base class for event publishing tests
  - Extends BaseIntegrationTest to inherit test infrastructure
  - Provides common setup for event capture

- `src/test/java/me/veselin/probity/common/domain/event/TestEventCapture.java`
  - Spring component for capturing events during tests
  - Uses @EventListener to capture all DomainEvent instances
  - Provides `getCapturedEvents()` and `clear()` methods
  - **Note**: Made public to allow access from test classes in different packages

- `src/test/java/me/veselin/probity/common/domain/event/ListenerIsolationTest.java`
  - Tests per-listener exception isolation
  - Uses @Order(1) and @Order(2) to guarantee listener execution order
  - One listener throws an exception, the other succeeds
  - Verifies that the succeeding listener still executes after the throwing listener
  - **Critical**: This test validates that the custom error handler in EventConfiguration works correctly

- `src/test/java/me/veselin/probity/portfolio/service/portfolio/PortfolioEventPublishingTest.java`
  - Extends BasePortfolioIntegrationTest to use seeded test data
  - Contains 6 tests, one for each PortfolioCommandService method:
    - `createPortfolio_firesPortfolioUpdatedEvent`
    - `addPosition_firesPortfolioUpdatedEvent`
    - `updatePosition_firesPortfolioUpdatedEvent`
    - `deletePosition_firesPortfolioUpdatedEvent`
    - `updatePortfolio_firesPortfolioUpdatedEvent`
    - `deletePortfolio_firesPortfolioUpdatedEvent`
  - Each test verifies:
    - Event is fired
    - Event is of correct type (PortfolioUpdatedEvent)
    - Event contains correct portfolioId and userId
    - Event has non-null occurredAt timestamp

- `src/test/java/me/veselin/probity/simulation/service/SimulationEventPublishingTest.java`
  - Extends BaseSimulationIntegrationTest to use seeded test data
  - Contains 2 tests:
    - `runSimulation_firesSimulationRequestedAndCompletedEvents` - verifies success path
    - `runSimulationWithEmptyPortfolio_firesSimulationRequestedAndFailedEventsAndRethrowsException` - verifies failure path
  - Success test verifies:
    - SimulationRequestedEvent fired with correct parameters
    - SimulationCompletedEvent fired with simulationId and portfolio value
  - Failure test verifies:
    - SimulationRequestedEvent fired before failure
    - SimulationFailedEvent fired with error message
    - Original exception is rethrown after event publication

## Potential Issues and Considerations

### 1. Transaction Boundaries
- **Concern**: Event publishing occurs within the same transaction as the domain operation
- **Impact**: If a listener performs database operations, they participate in the same transaction
- **Mitigation**: Listeners should be designed to be idempotent and handle transaction rollback scenarios
- **Recommendation**: Consider using `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` for listeners that need to run after transaction commit

### 2. Exception Handling in Listeners
- **Current Behavior**: The custom error handler logs exceptions but does not prevent other listeners from running
- **Impact**: Listeners that fail silently may not be noticed in production
- **Mitigation**: Error handler logs at ERROR level, which should trigger monitoring alerts
- **Recommendation**: Consider adding metrics to track listener failures for operational visibility

### 3. Event Ordering
- **Current Behavior**: Listener execution order is controlled by @Order annotations
- **Impact**: Without @Order, listeners execute in unspecified order
- **Mitigation**: All production listeners should use @Order to ensure predictable execution
- **Recommendation**: Document the expected listener order in the design note

### 4. Event Data Consistency
- **Concern**: Events contain domain entity IDs that may become stale if entities are deleted
- **Impact**: Listeners processing events after entity deletion may encounter missing data
- **Mitigation**: Listeners should handle missing entities gracefully
- **Recommendation**: Consider including sufficient data in events to make them self-contained for critical operations

### 5. Performance Impact
- **Concern**: Synchronous event publishing adds overhead to each operation
- **Impact**: Slow listeners can slow down the main operation
- **Mitigation**: Current implementation is synchronous by design; listeners should be fast
- **Recommendation**: Monitor operation latency after adding listeners; consider async publishing for non-critical listeners in future phases

### 6. Test Data Conflicts
- **Issue Encountered**: Initial test failures due to portfolio name conflicts between tests
- **Resolution**: Used unique portfolio names and relied on @Transactional for test isolation
- **Current State**: All tests pass with proper isolation
- **Recommendation**: Consider using randomized test data names to prevent future conflicts

### 7. Event Capture Component
- **Design Decision**: TestEventCapture is a public Spring component
- **Rationale**: Needed for access from test classes in different packages
- **Alternative**: Could have created separate capture components per package
- **Recommendation**: Consider if this should be moved to a test utilities package for better organization

## Test Coverage

### Unit Tests
- No new unit tests added; all tests are integration tests
- **Gap**: Consider adding unit tests for SynchronousDomainEventPublisher to verify delegation behavior

### Integration Tests
- 336 total tests pass (including existing tests)
- 8 new tests added:
  - 1 listener isolation test
  - 6 portfolio event publishing tests
  - 2 simulation event publishing tests
- All tests use @Transactional for proper data isolation
- All tests extend appropriate base classes to reuse test infrastructure

## Verification Steps Performed

1. Compiled all code successfully
2. Ran full test suite: 336 tests pass, 0 failures, 0 errors
3. Verified listener isolation with ordered listeners
4. Verified event publication for all 6 portfolio operations
5. Verified event publication for simulation success and failure paths
6. Verified exception rethrow after failure event publication
7. Verified custom ApplicationEventMulticaster bean configuration

## Recommendations for Future Work

1. **Add Metrics**: Instrument event publishing and listener execution with metrics for operational visibility
2. **Add Documentation**: Document expected listener order and behavior in design note
3. **Consider Async Events**: Evaluate if any listeners should be asynchronous in future phases
4. **Add Unit Tests**: Add unit tests for SynchronousDomainEventPublisher and event records
5. **Review Transaction Boundaries**: Evaluate if any listeners should run AFTER_COMMIT
6. **Add Event Schema Versioning**: Consider adding version fields to events for future schema evolution
7. **Add Event Replay Capability**: Consider if event replay is needed for debugging or recovery

## Conclusion

The implementation successfully adds synchronous domain event publishing to the portfolio and simulation services. All acceptance criteria have been met:
- Domain event infrastructure is in place
- Custom ApplicationEventMulticaster with error handler ensures per-listener exception isolation
- All portfolio operations publish PortfolioUpdatedEvent
- Simulation operations publish appropriate events for success and failure paths
- Comprehensive test coverage validates the implementation
- All tests pass with no regressions

The implementation is production-ready with the considerations and recommendations noted above for future enhancement.
