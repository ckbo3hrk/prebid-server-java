package org.prebid.server.it;

import com.github.tomakehurst.wiremock.client.VerificationException;
import com.github.tomakehurst.wiremock.matching.AnythingPattern;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.apache.commons.collections4.CollectionUtils;
import org.json.JSONException;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.prebid.server.deals.LineItemService;
import org.prebid.server.deals.proto.report.DeliveryProgressReport;
import org.prebid.server.deals.proto.report.Event;
import org.prebid.server.deals.proto.report.LineItemStatus;
import org.skyscreamer.jsonassert.ArrayValueMatcher;
import org.skyscreamer.jsonassert.Customization;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.ValueMatcher;
import org.skyscreamer.jsonassert.comparator.CustomComparator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@RunWith(SpringRunner.class)
@TestPropertySource(locations = {"deals/test-deals-application.properties"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class DealsTest extends IntegrationTest {

    private static final int APP_PORT = 9080;

    private static final String RUBICON = "rubicon";

    private static final RequestSpecification SPEC = spec(APP_PORT);

    @Autowired
    private LineItemService lineItemService;

    @Autowired
    private Clock clock;

    @BeforeClass
    public static void setUpInner() throws IOException {
        // given
        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/delivery-stats-progress"))
                .withBasicAuth("username", "password")
                .withHeader("pg-trx-id", new AnythingPattern())
                .willReturn(aResponse()));

        WIRE_MOCK_RULE.stubFor(get(urlPathEqualTo("/planner-plan"))
                .withQueryParam("instanceId", equalTo("localhost"))
                .withQueryParam("region", equalTo("local"))
                .withQueryParam("vendor", equalTo("local"))
                .withBasicAuth("username", "password")
                .withHeader("pg-trx-id", new AnythingPattern())
                .willReturn(aResponse().withBody(plannerResponseFrom("deals/test-planner-plan-response-1.json"))));

        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/planner-register"))
                .withBasicAuth("username", "password")
                .withHeader("pg-trx-id", new AnythingPattern())
                .withRequestBody(equalToJson(jsonFrom("deals/test-planner-register-request-1.json"), false, true))
                .willReturn(aResponse().withBody(jsonFrom("deals/test-planner-register-response.json"))));

        // pre-bid cache
        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/cache"))
                .withRequestBody(equalToJson(jsonFrom(
                        "deals/test-cache-deals-request.json"), true, false))
                .willReturn(aResponse()
                        .withTransformers("cache-response-transformer")
                        .withTransformerParameter("matcherName", "deals/test-cache-matcher.json")));
    }

    @Test
    public void openrtb2AuctionShouldRespondWithDealBids() throws IOException, JSONException {
        // given
        awaitForLineItemMetadata();

        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/user-data-win-event"))
                .withRequestBody(equalToJson(jsonFrom("deals/test-user-data-win-event-request-1.json"), false, true))
                .willReturn(aResponse()));

        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/user-data-details"))
                .withRequestBody(equalToJson(jsonFrom("deals/test-user-data-details-request-1.json"), false, true))
                .willReturn(aResponse().withBody(jsonFrom("deals/test-user-data-details-response-1.json"))));

        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/rubicon-exchange"))
                .withRequestBody(equalToJson(jsonFrom("deals/test-rubicon-bid-request-1.json"), false, true))
                .willReturn(aResponse().withBody(jsonFrom("deals/test-rubicon-bid-response-1.json"))));

        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/rubicon-exchange"))
                .withRequestBody(equalToJson(jsonFrom("deals/test-rubicon-bid-request-2.json"), false, true))
                .willReturn(aResponse()
                        .withFixedDelay(300)
                        .withBody(jsonFrom("deals/test-rubicon-bid-response-2.json"))));

        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/rubicon-exchange"))
                .withRequestBody(equalToJson(jsonFrom("deals/test-rubicon-bid-request-3.json"), false, true))
                .willReturn(aResponse().withBody(jsonFrom("deals/test-rubicon-bid-response-3.json"))));

        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/rubicon-exchange"))
                .withRequestBody(equalToJson(jsonFrom("deals/test-rubicon-bid-request-4.json"), false, true))
                .willReturn(aResponse().withBody(jsonFrom("deals/test-rubicon-bid-response-4.json"))));

        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/rubicon-exchange"))
                .withRequestBody(equalToJson(jsonFrom("deals/test-rubicon-bid-request-5.json"), false, true))
                .willReturn(aResponse()
                        .withFixedDelay(600)
                        .withBody(jsonFrom("deals/test-rubicon-bid-response-5.json"))));

        // when
        final Response response = given(SPEC)
                .header("Referer", "http://www.example.com")
                .header("User-Agent", "userAgent")
                // this uids cookie value stands for {"uids":{"rubicon":"J5VLCWQP-26-CWFT"}}
                .cookie("uids", "eyJ1aWRzIjp7InJ1Ymljb24iOiJKNVZMQ1dRUC0yNi1DV0ZUIn19")
                .body(jsonFrom("deals/test-auction-request.json"))
                .post("/openrtb2/auction");

        // then
        final String expectedAuctionResponse = withTemporalFields(openrtbAuctionResponseFrom(
                "deals/test-auction-response.json", response, singletonList(RUBICON)));
        JSONAssert.assertEquals(expectedAuctionResponse, response.asString(), openrtbDeepDebugTimeComparator());

        // when
        final Response eventResponse = given(SPEC)
                .queryParam("t", "win")
                .queryParam("b", "bidId")
                .queryParam("a", "14062")
                .queryParam("l", "lineItem1")
                .queryParam("f", "i")
                // this uids cookie value stands for {"uids":{"rubicon":"J5VLCWQP-26-CWFT"}}
                .cookie("uids", "eyJ1aWRzIjp7InJ1Ymljb24iOiJKNVZMQ1dRUC0yNi1DV0ZUIn19")
                .get("/event");

        // then
        assertThat(eventResponse.getStatusCode()).isEqualTo(200);

        await().atMost(1, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).until(() ->
                verify(() -> WIRE_MOCK_RULE.verify(postRequestedFor(urlPathEqualTo("/user-data-win-event")))));

        // verify delivery stats report
        await().atMost(10, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() ->
                verify(() -> WIRE_MOCK_RULE.verify(2, postRequestedFor(urlPathEqualTo("/delivery-stats-progress")))));

        final String expectedRequestBody = jsonFrom("deals/test-delivery-stats-progress-request-1.json");

        final List<LoggedRequest> requestList = WIRE_MOCK_RULE.findAll(
                postRequestedFor(urlPathEqualTo("/delivery-stats-progress")));

        final DeliveryProgressReport report = chooseReportToCompare(requestList);

        JSONAssert.assertEquals(expectedRequestBody, mapper.writeValueAsString(report), JSONCompareMode.LENIENT);
    }

    private static String plannerResponseFrom(String templatePath) throws IOException {
        final ZonedDateTime now = ZonedDateTime.now().withFixedOffsetZone();

        return jsonFrom(templatePath)
                .replaceAll("\\{\\{ now }}", now.toString())
                .replaceAll("\\{\\{ lineItem.startTime }}", now.minusDays(5).toString())
                .replaceAll("\\{\\{ lineItem.endTime }}", now.plusDays(5).toString())
                .replaceAll("\\{\\{ plan.startTime }}", now.minusHours(1).toString())
                .replaceAll("\\{\\{ plan.endTime }}", now.plusHours(1).toString());
    }

    private String withTemporalFields(String auctionResponse) {
        final ZonedDateTime dateTime = ZonedDateTime.now(clock);

        return auctionResponse
                .replaceAll("\"?\\{\\{ userdow }}\"?", Integer.toString(
                        dateTime.getDayOfWeek().get(WeekFields.SUNDAY_START.dayOfWeek())))
                .replaceAll("\"?\\{\\{ userhour }}\"?", Integer.toString(dateTime.getHour()));
    }

    private void awaitForLineItemMetadata() {
        await().atMost(2, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> lineItemService.accountHasDeals("2001", ZonedDateTime.now(clock)));
    }

    /**
     * Timestamps in response are always generated anew.
     * This comparator allows to just verify they are present and parsable.
     */
    private static CustomComparator openrtbDeepDebugTimeComparator() {
        final ValueMatcher<Object> timeValueMatcher = (actual, expected) -> {
            try {
                return mapper.readValue("\"" + actual.toString() + "\"", ZonedDateTime.class) != null;
            } catch (IOException e) {
                return false;
            }
        };

        final ArrayValueMatcher<Object> arrayValueMatcher = new ArrayValueMatcher<>(new CustomComparator(
                JSONCompareMode.NON_EXTENSIBLE,
                new Customization("ext.debug.trace.deals[*].time", timeValueMatcher)));

        final List<Customization> arrayValueMatchers = IntStream.range(1, 5)
                .mapToObj(i -> new Customization("ext.debug.trace.lineitems.lineItem" + i,
                        new ArrayValueMatcher<>(new CustomComparator(
                                JSONCompareMode.NON_EXTENSIBLE,
                                new Customization("ext.debug.trace.lineitems.lineItem" + i + "[*].time",
                                        timeValueMatcher)))))
                .collect(Collectors.toList());

        arrayValueMatchers.add(new Customization("ext.debug.trace.deals", arrayValueMatcher));
        return new CustomComparator(JSONCompareMode.NON_EXTENSIBLE,
                arrayValueMatchers.toArray(new Customization[0]));
    }

    private static boolean verify(Runnable verify) {
        try {
            verify.run();
            return true;
        } catch (VerificationException e) {
            return false;
        }
    }

    private static DeliveryProgressReport chooseReportToCompare(List<LoggedRequest> requestList)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        final DeliveryProgressReport firstReport = mapper.readValue(requestList.get(0).getBodyAsString(),
                DeliveryProgressReport.class);
        final DeliveryProgressReport secondReport = mapper.readValue(requestList.get(1).getBodyAsString(),
                DeliveryProgressReport.class);

        // in a reason cron high dependent on time value, report with statistic should be chosen
        final DeliveryProgressReport report = firstReport.getClientAuctions() != 0 ? firstReport : secondReport;
        final LineItemStatus lineItem1 = firstReport.getLineItemStatus().stream()
                .filter(lineItemStatus -> lineItemStatus.getLineItemId().equals("lineItem1"))
                .findFirst().orElse(null);

        // if report does not contain win event for lineItem1 it is possible that it got by the second report
        if (lineItem1 != null && CollectionUtils.isEmpty(lineItem1.getEvents())) {
            final Set<Event> mergedEvents = lineItem1.getEvents();

            firstReport.getLineItemStatus().stream()
                    .filter(lineItemStatus -> lineItemStatus.getLineItemId().equals("lineItem1"))
                    .map(LineItemStatus::getEvents)
                    .filter(CollectionUtils::isNotEmpty)
                    .findFirst()
                    .ifPresent(mergedEvents::addAll);

            secondReport.getLineItemStatus().stream()
                    .filter(lineItemStatus -> lineItemStatus.getLineItemId().equals("lineItem1"))
                    .map(LineItemStatus::getEvents)
                    .filter(CollectionUtils::isNotEmpty)
                    .findFirst()
                    .ifPresent(mergedEvents::addAll);
        }

        return report;
    }
}
