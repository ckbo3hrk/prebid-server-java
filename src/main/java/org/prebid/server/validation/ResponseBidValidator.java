package org.prebid.server.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Deal;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Pmp;
import com.iab.openrtb.response.Bid;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.request.ExtDeal;
import org.prebid.server.proto.openrtb.ext.request.ExtDealLine;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.validation.model.ValidationResult;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Validator for response {@link Bid} object.
 */
public class ResponseBidValidator {

    private static final String BIDDER_EXT = "bidder";
    private static final String DEALS_ONLY = "dealsonly";

    private static final Logger logger = LoggerFactory.getLogger(ResponseBidValidator.class);

    private final JacksonMapper mapper;
    private final boolean dealsEnabled;

    public ResponseBidValidator(JacksonMapper mapper, boolean dealsEnabled) {
        this.mapper = Objects.requireNonNull(mapper);
        this.dealsEnabled = dealsEnabled;
    }

    public ValidationResult validate(BidderBid bidderBid, BidRequest bidRequest) {
        try {
            validateFieldsFor(bidderBid.getBid());
            if (dealsEnabled) {
                validateDealsFor(bidderBid, bidRequest);
            }
        } catch (ValidationException e) {
            return ValidationResult.error(e.getMessage());
        }
        return ValidationResult.success();
    }

    private static void validateFieldsFor(Bid bid) throws ValidationException {
        if (bid == null) {
            throw new ValidationException("Empty bid object submitted.");
        }

        final String bidId = bid.getId();
        if (StringUtils.isBlank(bidId)) {
            throw new ValidationException("Bid missing required field 'id'");
        }

        if (StringUtils.isBlank(bid.getImpid())) {
            throw new ValidationException("Bid \"%s\" missing required field 'impid'", bidId);
        }

        final BigDecimal price = bid.getPrice();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Bid \"%s\" does not contain a positive 'price'", bidId);
        }

        if (StringUtils.isEmpty(bid.getCrid())) {
            throw new ValidationException("Bid \"%s\" missing creative ID", bidId);
        }
    }

    private void validateDealsFor(BidderBid bidderBid, BidRequest bidRequest) throws ValidationException {
        final Bid bid = bidderBid.getBid();
        final String bidId = bid.getId();

        final Imp imp = bidRequest.getImp().stream()
                .filter(curImp -> Objects.equals(curImp.getId(), bid.getImpid()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Bid \"%s\" has no corresponding imp in request", bidId));

        final String dealId = bid.getDealid();

        if (isDealsOnlyImp(imp) && dealId == null) {
            throw new ValidationException("Bid \"%s\" missing required field 'dealid'", bidId);
        }

        if (dealId != null) {
            final Set<String> dealIdsFromImp = getDealIdsFromImp(imp);
            if (!dealIdsFromImp.contains(dealId)) {
                throw new ValidationException(
                        "Bid \"%s\" has 'dealid' not present in corresponding imp in request. 'dealid' in bid: "
                                + "'%s', deal Ids in imp: '%s'", bidId, dealId, String.join(",", dealIdsFromImp));
            }

            if (bidderBid.getType() == BidType.banner) {
                if (imp.getBanner() == null) {
                    throw new ValidationException("Bid \"%s\" has banner media type but corresponding imp in request "
                            + "is missing 'banner' object", bidId);
                }

                final List<Format> bannerFormats = getBannerFormats(imp);
                if (bidSizeNotInFormats(bid, bannerFormats)) {
                    throw new ValidationException("Bid \"%s\" has 'w' and 'h' not supported by corresponding imp in "
                            + "request. Bid dimensions: '%dx%d', formats in imp: '%s'", bidId, bid.getW(), bid.getH(),
                            formatSizes(bannerFormats));
                }

                final List<Format> lineItemSizes = getLineItemSizes(imp);
                if (bidSizeNotInFormats(bid, lineItemSizes)) {
                    throw new ValidationException("Bid \"%s\" has 'w' and 'h' not matched to Line Item. Bid "
                            + "dimensions: '%dx%d', Line Item sizes: '%s'", bidId, bid.getW(), bid.getH(),
                            formatSizes(lineItemSizes));
                }
            }
        }
    }

    private static boolean isDealsOnlyImp(Imp imp) {
        final JsonNode dealsOnlyNode = imp.getExt().get(BIDDER_EXT).get(DEALS_ONLY);
        return dealsOnlyNode != null && dealsOnlyNode.isBoolean() && dealsOnlyNode.asBoolean();
    }

    private static Set<String> getDealIdsFromImp(Imp imp) {
        return getDeals(imp)
                .filter(Objects::nonNull)
                .map(Deal::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static Stream<Deal> getDeals(Imp imp) {
        final Pmp pmp = imp.getPmp();
        return pmp != null ? pmp.getDeals().stream() : Stream.empty();
    }

    private static boolean bidSizeNotInFormats(Bid bid, List<Format> formats) {
        return formats.stream()
                .noneMatch(format -> sizesEqual(bid, format));
    }

    private static boolean sizesEqual(Bid bid, Format format) {
        return Objects.equals(format.getH(), bid.getH()) && Objects.equals(format.getW(), bid.getW());
    }

    private static List<Format> getBannerFormats(Imp imp) {
        return ListUtils.emptyIfNull(imp.getBanner().getFormat());
    }

    private List<Format> getLineItemSizes(Imp imp) {
        return getDeals(imp)
                .map(Deal::getExt)
                .filter(Objects::nonNull)
                .map(this::dealExt)
                .filter(Objects::nonNull)
                .map(ExtDeal::getLine)
                .filter(Objects::nonNull)
                .map(ExtDealLine::getSizes)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private ExtDeal dealExt(JsonNode ext) {
        try {
            return mapper.mapper().treeToValue(ext, ExtDeal.class);
        } catch (JsonProcessingException e) {
            logger.warn("Error decoding deal.ext: {0}", e, e.getMessage());
            return null;
        }
    }

    private static String formatSizes(List<Format> lineItemSizes) {
        return lineItemSizes.stream()
                .map(format -> String.format("%dx%d", format.getW(), format.getH()))
                .collect(Collectors.joining(","));
    }
}
