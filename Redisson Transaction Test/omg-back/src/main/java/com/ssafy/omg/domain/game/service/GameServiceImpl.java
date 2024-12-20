package com.ssafy.omg.domain.game.service;

import com.ssafy.omg.config.baseresponse.BaseException;
import com.ssafy.omg.config.baseresponse.MessageException;
import com.ssafy.omg.domain.arena.entity.Arena;
import com.ssafy.omg.domain.game.GameRepository;
import com.ssafy.omg.domain.game.dto.IndividualMessageDto;
import com.ssafy.omg.domain.game.dto.PlayerMoveRequest;
import com.ssafy.omg.domain.game.dto.StockMarketResponse;
import com.ssafy.omg.domain.game.dto.StockRequest;
import com.ssafy.omg.domain.game.entity.Game;
import com.ssafy.omg.domain.game.entity.GameEvent;
import com.ssafy.omg.domain.game.entity.GameStatus;
import com.ssafy.omg.domain.game.entity.StockInfo;
import com.ssafy.omg.domain.game.entity.StockState;
import com.ssafy.omg.domain.game.repository.GameEventRepository;
import com.ssafy.omg.domain.player.entity.Player;
import com.ssafy.omg.domain.player.entity.PlayerStatus;
import com.ssafy.omg.domain.socket.dto.StompPayload;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.ssafy.omg.config.baseresponse.BaseResponseStatus.ARENA_NOT_FOUND;
import static com.ssafy.omg.config.baseresponse.BaseResponseStatus.EVENT_NOT_FOUND;
import static com.ssafy.omg.config.baseresponse.BaseResponseStatus.EXCEEDS_DIFF_RANGE;
import static com.ssafy.omg.config.baseresponse.BaseResponseStatus.IMPOSSIBLE_STOCK_CNT;
import static com.ssafy.omg.config.baseresponse.BaseResponseStatus.INSUFFICIENT_STOCK;
import static com.ssafy.omg.config.baseresponse.BaseResponseStatus.INVALID_BLACK_TOKEN;
import static com.ssafy.omg.config.baseresponse.BaseResponseStatus.INVALID_ROUND;
import static com.ssafy.omg.config.baseresponse.BaseResponseStatus.INVALID_SELL_STOCKS;
import static com.ssafy.omg.config.baseresponse.BaseResponseStatus.INVALID_STOCK_GROUP;
import static com.ssafy.omg.config.baseresponse.BaseResponseStatus.INVALID_STOCK_LEVEL;
import static com.ssafy.omg.config.baseresponse.BaseResponseStatus.PLAYER_NOT_FOUND;
import static com.ssafy.omg.config.baseresponse.BaseResponseStatus.PLAYER_STATE_ERROR;
import static com.ssafy.omg.config.baseresponse.BaseResponseStatus.REQUEST_ERROR;
import static com.ssafy.omg.config.baseresponse.MessageResponseStatus.AMOUNT_EXCEED_CASH;
import static com.ssafy.omg.config.baseresponse.MessageResponseStatus.AMOUNT_EXCEED_DEBT;
import static com.ssafy.omg.config.baseresponse.MessageResponseStatus.AMOUNT_OUT_OF_RANGE;
import static com.ssafy.omg.config.baseresponse.MessageResponseStatus.INSUFFICIENT_CASH;
import static com.ssafy.omg.config.baseresponse.MessageResponseStatus.LOAN_ALREADY_TAKEN;
import static com.ssafy.omg.config.baseresponse.MessageResponseStatus.OUT_OF_CASH;
import static com.ssafy.omg.config.baseresponse.MessageResponseStatus.STOCK_NOT_AVAILABLE;
import static com.ssafy.omg.domain.game.entity.RoundStatus.STOCK_FLUCTUATION;
import static com.ssafy.omg.domain.game.entity.RoundStatus.TUTORIAL;
import static com.ssafy.omg.domain.player.entity.PlayerStatus.COMPLETED;
import static com.ssafy.omg.domain.player.entity.PlayerStatus.NOT_STARTED;
import static org.hibernate.query.sqm.tree.SqmNode.log;

@Service
@RequiredArgsConstructor
public class GameServiceImpl implements GameService {

    private final RedisTemplate<String, Arena> redisTemplate;
    private final RedissonClient redissonClient;
    // Redis에서 대기방 식별을 위한 접두사 ROOM_PREFIX 설정
    private static final String ROOM_PREFIX = "room";
    private final int[][] LOAN_RANGE = new int[][]{{50, 100}, {150, 300}, {500, 1000}};
    private static List<Integer> characterTypes = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
    private final GameEventRepository gameEventRepository;
    private final GameRepository gameRepository;
    private final StockState stockState;
    private Random random = new Random();

    /**
     * 진행중인 게임의 리스트를 반환 ( 모든 진행중인 게임들을 관리 )
     *
     * @return
     * @throws BaseException
     */
    @Override
    public List<Game> getAllActiveGames() throws BaseException {
        List<Arena> allArenas = gameRepository.findAllArenas();
        return allArenas.stream()
                .map(Arena::getGame)
                .filter(game -> game != null && game.getGameStatus() == GameStatus.IN_GAME)
                .collect(Collectors.toList());
    }

    /**
     * 거래소에서 응답으로 보낼 DTO 생성 메서드
     *
     * @param roomId
     * @param sender
     * @return
     * @throws BaseException
     */
    @Override
    public IndividualMessageDto getIndividualMessage(String roomId, String sender) throws BaseException {

        Arena arena = gameRepository.findArenaByRoomId(roomId)
                .orElseThrow(() -> new BaseException(ARENA_NOT_FOUND));
        Game game = arena.getGame();
        Player player = findPlayer(arena, sender);

        return IndividualMessageDto.builder()
                .hasLoan(player.getHasLoan())
                .loanPrincipal(player.getLoanPrincipal())
                .loanInterest(player.getLoanInterest())
                .totalDebt(player.getTotalDebt())
                .cash(player.getCash())
                .stock(player.getStock())
                .goldOwned(player.getGoldOwned())
                .carryingStocks(player.getCarryingStocks())
                .carryingGolds(player.getCarryingGolds())
                .action(player.getAction())
                .state(player.getState())
                .build();
    }

    /**
     * 주식 거래소 정보 생성
     *
     * @param game
     * @return StockMarketInfo
     */
    @Override
    public StockMarketResponse createStockMarketInfo(Game game) {
        List<Player> players = game.getPlayers();
        String[] playerNicknames = players.stream()
                .map(Player::getNickname)
                .toList().toArray(new String[0]);

        StockInfo[] marketStocks = game.getMarketStocks();
        int[][] playerStockShares = new int[6][4];
        int[] leftStocks = new int[6];
        int[] stockPrices = new int[6];
//        int[] recentStockPriceChanges = new int[6];

        for (int i = 1; i < 6; i++) {
            // 플레이어 별 보유 주식 개수 (r: 주식 종류 , c: 플레이어 , value: 주식 개수)
            for (int j = 0; j < 4; j++) {
                playerStockShares[i][j] = players.get(j).getStock()[i];
            }

            // 주식 별 남은 주식 개수
            leftStocks[i] = marketStocks[i].getCnt();

            // 주가
            stockPrices[i] = stockState.getStockStandard()[marketStocks[i].getState()[0]][marketStocks[i].getState()[1]].getPrice();

            // 최근 거래 변동값
//            recentStockPriceChanges[i] = marketStocks[i].getRecentTransaction();
        }

        return StockMarketResponse.builder()
                .stockPriceChangeInfo(game.getStockPriceChangeInfo())
                .playerNicknames(playerNicknames)
                .playerStockShares(playerStockShares)
                .leftStocks(leftStocks)
                .stockPrices(stockPrices)
                .build();
    }

    /**
     * Game의 값이 변경됨에 따라 바뀐 값을 Arena에 반영하여 redis에 업데이트
     *
     * @param game
     * @throws BaseException
     */
    @Override
    public void saveGame(Game game) throws BaseException {
        Arena arena = gameRepository.findArenaByRoomId(game.getGameId())
                .orElseThrow(() -> new BaseException(ARENA_NOT_FOUND));
        arena.setGame(game);
        gameRepository.saveArena(game.getGameId(), arena);
    }

    /**
     * 게임 초기값 설정
     * 게임 시작 전 고정된 초기값을 미리 세팅하여 게임 준비를 함.
     *
     * @param roomId        게임 방 코드
     * @param inRoomPlayers 게임 참여 유저 리스트
     * @return Arena
     * @throws BaseException PLAYER_NOT_FOUND
     */
    @Override
    public Arena initializeGame(String roomId, List<String> inRoomPlayers) throws BaseException {
        if (inRoomPlayers == null || inRoomPlayers.isEmpty()) {
            throw new BaseException(PLAYER_NOT_FOUND);
        }

        Arena arena = redisTemplate.opsForValue().get(ROOM_PREFIX + roomId);
        if (arena != null) {
            List<Player> players = new ArrayList<>();
            int[] pocket = new int[]{0, 23, 23, 23, 23, 23};
            StockInfo[] market = initializeMarket();
            putRandomStockIntoMarket(pocket, market);

            // 캐릭터 종류
            characterTypes = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
            Collections.shuffle(characterTypes);

            for (int i = 0; i < inRoomPlayers.size(); i++) {
                int[] randomStock = generateRandomStock();
                // pocket에서 뽑은 randomStock 만큼 빼주기
                for (int j = 1; j < randomStock.length; j++) {
                    pocket[j] -= randomStock[j];
                    if (pocket[j] < 0) {
                        throw new BaseException(INSUFFICIENT_STOCK);
                    }
                }

                int characterType = characterTypes.remove(0);

                Player newPlayer = Player.builder()
                        .nickname(inRoomPlayers.get(i))   // 플레이어 닉네임
                        .characterType(characterType)     // 캐릭터 에셋 종류
                        .characterMovement(false)         // 줍기 행동 유무
                        .position(new double[]{0, 0, 0})  // TODO 임시로 (0,0,0)으로 해뒀습니다 고쳐야함
                        .direction(new double[]{0, 0, 0}) // TODO 임시로 (0,0,0)으로 해뒀습니다 고쳐야함
                        .carryingStocks(new int[]{0, 0, 0, 0, 0, 0})
                        .carryingGolds(0)

                        .hasLoan(0)                       // 대출 유무
                        .loanPrincipal(0)                 // 대출원금
                        .loanInterest(0)                  // 이자
                        .totalDebt(0)                     // 갚아야 할 금액
                        .cash(100)                        // 현금
                        .stock(randomStock)               // 보유 주식 개수
                        .goldOwned(0)                     // 보유 금괴 개수

                        .action(null)                     // 플레이어 행위 (주식 매수, 주식 매도, 금괴 매입, 대출, 상환)
                        .state(NOT_STARTED)               // 플레이어 행위 상태 (시작전, 진행중, 완료)
                        .isConnected(1)                   // 플레이어 접속 상태 (0: 끊김, 1: 연결됨)
                        .build();
                players.add(newPlayer);
            }

            int[] randomEvent = generateRandomEvent();

            Game newGame = Game.builder()
                    .gameId(roomId)
                    .gameStatus(GameStatus.IN_GAME)          // 게임 대기 상태로 시작
                    .message("GAME_START")
                    .players(players)

                    .time(20)
                    .round(1)                                     // 시작 라운드 1
                    .roundStatus(TUTORIAL)

                    .currentInterestRate(5)                       // 예: 초기 금리 5%로 설정
                    .economicEvent(randomEvent)                   // 초기 경제 이벤트 없음
                    .currentEvent(null)                           // 적용할 경제이벤트 없음
                    .currentStockPriceLevel(0)                    // 주가 수준

                    .stockTokensPocket(pocket)                    // 주머니 초기화
                    .marketStocks(market)                         // 주식 시장 초기화
                    .stockSellTrack(new int[]{1, 2, 2, 2, 2, 2})  // 주식 매도 트랙 초기화
                    .stockBuyTrack(new int[6])                    // 주식 매수 트랙 초기화
                    .goldBuyTrack(new int[6])                     // 금 매입 트랙 초기화

                    .goldPrice(20)                                // 초기 금 가격 20
                    .goldPriceIncreaseCnt(0)                      // 초기 금괴 매입 개수 0

                    .stockPriceChangeInfo(new int[6][61])
                    .build();

            arena.setGame(newGame);
            arena.setMessage("GAME_INITIALIZED");
            arena.setRoom(null);
            gameRepository.saveArena(roomId, arena);
        } else {
            throw new BaseException(ARENA_NOT_FOUND);
        }
        return arena;
    }

    private StockInfo[] initializeMarket() {
        StockInfo[] market = new StockInfo[6];
        market[0] = new StockInfo(0, new int[]{0, 0});

        for (int i = 1; i < 6; i++) {
            market[i] = new StockInfo(8, new int[]{12, 3});
        }

        return market;
    }

    /**
     * 경제 이벤트 발생(조회) 및 금리 변동 (2~10라운드)
     *
     * @param roomId 방 코드
     * @return 경제 이벤트 정보 반환
     * @throws BaseException
     */
    @Override
    public GameEvent createGameEventNews(String roomId) throws BaseException {
        Arena arena = gameRepository.findArenaByRoomId(roomId)
                .orElseThrow(() -> new BaseException(ARENA_NOT_FOUND));
        Game game = arena.getGame();

        int currentRound = game.getRound();
        if (currentRound < 1 || currentRound >= 10) {
            log.info("경제 뉴스는 1라운드부터 9라운드까지 발생합니다.");
            throw new BaseException(EVENT_NOT_FOUND);
        }

        int[] economicEvent = game.getEconomicEvent();
        if (economicEvent == null) {
            log.error("경제 이벤트 배열이 null이거나 현재 라운드에 해당하는 이벤트가 없습니다.");
            throw new BaseException(EVENT_NOT_FOUND);
        }

        Long eventId = (long) economicEvent[currentRound];
        GameEvent gameEvent = gameEventRepository.findById(eventId)
                .orElseThrow(() -> new BaseException(EVENT_NOT_FOUND));

        // 현재 발생한(다음 라운드에 반영될) 경제 뉴스를 currentEvent로 설정

        System.out.println("======================발행할 때=====================");
        System.out.println("적용할 이벤트 : " + gameEvent.getTitle());
        System.out.println("=================================================");

        // 현재 발생한(다음 라운드에 반영될) 경제 뉴스를 currentEvent로 설정
        game.setCurrentEvent(gameEvent);

        // Arena 객체에 수정된 Game 객체를 다시 설정
        arena.setGame(game);

        // 수정된 Arena를 Redis에 저장
        gameRepository.saveArena(roomId, arena);

        // 저장 후 즉시 다시 조회하여 확인
        Arena savedArena = gameRepository.findArenaByRoomId(roomId)
                .orElseThrow(() -> new BaseException(ARENA_NOT_FOUND));
        GameEvent savedEvent = savedArena.getGame().getCurrentEvent();
        System.out.println("Saved currentEvent: " + (savedEvent != null ? savedEvent.getTitle() : "null"));


        return game.getCurrentEvent();
    }

    /**
     * 전 라운드의 경제 이벤트를 현 라운드에 적용 ( 금리 및 주가 변동 )
     *
     * @param roomId
     * @return appliedEvent
     * @throws BaseException
     */
    @Override
    public GameEvent applyEconomicEvent(String roomId) throws BaseException {
        Arena arena = gameRepository.findArenaByRoomId(roomId)
                .orElseThrow(() -> new BaseException(ARENA_NOT_FOUND));
        Game game = arena.getGame();

        int currentRound = game.getRound();
        if (currentRound < 2 || currentRound > 10) {
            log.info("경제 이벤트 적용은 2라운드부터 10라운드까지 발생합니다.");
            throw new BaseException(INVALID_ROUND);
        }

        GameEvent currentEvent = game.getCurrentEvent();
        if (currentEvent == null) {
            log.warn("현재 이벤트가 null입니다. 이전 라운드에서 이벤트가 설정되지 않았을 수 있습니다.");
//            throw new BaseException(EVENT_NOT_FOUND);
            return null;
        }
        log.warn("Before applying event - Interest Rate: " + game.getCurrentInterestRate());

        // 금리 및 주가 변동 반영
        // 1. 금리 변동
        int currentInterestRate = game.getCurrentInterestRate();
        currentInterestRate += currentEvent.getValue();
        currentInterestRate = Math.max(1, Math.min(currentInterestRate, 10));
        game.setCurrentInterestRate(currentInterestRate);

        // 2. 주가 변동
        StockInfo[] marketStocks = game.getMarketStocks();
        String affectedStockGroup = currentEvent.getAffectedStockGroup();
        int eventValue = currentEvent.getValue();


// 주가 변동 전후 로깅
        for (int i = 1; i < marketStocks.length; i++) {
            log.warn("Before - Stock {}: " + Arrays.toString(marketStocks[i].getState()));
        }


        try {
            switch (affectedStockGroup) {
                case "ALL":
                    for (int i = 1; i < marketStocks.length; i++) {
                        modifyStockPrice(marketStocks[i], eventValue);
                    }
                    break;
                case "FOOD":
                    for (int i = 1; i <= 2; i++) {
                        modifyStockPrice(marketStocks[i], eventValue);
                    }
                    break;
                case "GIFT":
                    modifyStockPrice(marketStocks[3], eventValue);
                    break;
                case "CLOTHES":
                    for (int i = 4; i <= 5; i++) {
                        modifyStockPrice(marketStocks[i], eventValue);
                    }
                    break;
                case "NULL":
                    break;
                default:
                    throw new BaseException(INVALID_STOCK_GROUP);
            }
        } catch (BaseException e) {
            System.out.println("주가 변동 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }

        game.setMarketStocks(marketStocks);
        arena.setGame(game);

        GameEvent appliedEvent = currentEvent;


        // 이벤트 적용 코드
        log.warn("After applying event - Interest Rate: " + game.getCurrentInterestRate());
// 주가 변동 코드
        for (int i = 1; i < marketStocks.length; i++) {
            log.warn("After - Stock " + Arrays.toString(marketStocks[i].getState()));
        }


        // 수정된 Arena를 Redis에 저장
        gameRepository.saveArena(roomId, arena);


// 저장 후 즉시 확인
        Arena savedArena = gameRepository.findArenaByRoomId(roomId)
                .orElseThrow(() -> new BaseException(ARENA_NOT_FOUND));
        log.warn("Saved Interest Rate: " + savedArena.getGame().getCurrentInterestRate());
        for (int i = 1; i < savedArena.getGame().getMarketStocks().length; i++) {
            log.warn("Saved Stock " + Arrays.toString(savedArena.getGame().getMarketStocks()[i].getState()));
        }
        return appliedEvent;
    }

    private void modifyStockPrice(StockInfo stockInfo, int eventValue) throws BaseException {
        if (eventValue > 0) {
            stockInfo.increaseState();
        } else if (eventValue < 0) {
            stockInfo.decreaseState();
        }
    }

    private int[] putRandomStockIntoMarket(int[] pocket, StockInfo[] market) throws BaseException {
        int totalCount = 20;
        int[] count = new int[5];

        // 각각 최소1개씩
        for (int i = 0; i < 5; i++) {
            count[i] = 1;
            totalCount -= 1;
        }

        // 나머지 랜덤 나누기
        while (totalCount > 0) {
            int index = random.nextInt(5);
            if (count[index] < 7) {  // 한 주식당 최대 7개로 제한 (1 + 최대 6)
                count[index]++;
                totalCount--;
            }
        }

        // pocket에서 주식을 빼고 market에 넣기
        for (int i = 0; i < 5; i++) {
            if (pocket[i + 1] < count[i]) {
                throw new BaseException(INSUFFICIENT_STOCK);
            }
            pocket[i + 1] -= count[i];
            market[i + 1].setCnt(market[i + 1].getCnt() + count[i]);
        }

        int maxCnt = Arrays.stream(count).max().getAsInt();
        int minCnt = Arrays.stream(count).min().getAsInt();
        for (int i = 0; i < 5; i++) {
            if (count[i] == maxCnt) {
                int[] currentState = market[i + 1].getState();
                market[i + 1].setState(new int[]{currentState[0], currentState[1] - 1});
            }
            if (count[i] == minCnt) {
                int[] currentState = market[i + 1].getState();
                market[i + 1].setState(new int[]{currentState[0], currentState[1] + 1});
            }
        }

        return pocket;
    }

    public int[] generateRandomStock() throws BaseException {
        int[] result = new int[6];
        result[0] = 0;
        int remainingStockCounts = 5;

        for (int i = 1; i < 5; i++) {
            if (remainingStockCounts > 0) {
                // 최소값은 0, 최대값은 남은 주식수와 3중 작은값
                int max = Math.min(remainingStockCounts, 3);
                result[i] = random.nextInt(max + 1);
                remainingStockCounts -= result[i];
            } else {
                result[i] = 0;
            }
        }
        result[5] = remainingStockCounts;
        return result;
    }

    private int[] generateRandomEvent() throws BaseException {
        Set<Integer> selectedEconomicEvents = new HashSet<>();
        int[] result = new int[11];
        for (int i = 1; i < result.length - 1; i++) {
            int eventIdx;
            int attempts = 0;
            do {
                eventIdx = random.nextInt(22) + 1;
                if (attempts > 50) {
                    throw new BaseException(EVENT_NOT_FOUND);
                }
            } while (selectedEconomicEvents.contains(eventIdx));
            result[i] = eventIdx;
            selectedEconomicEvents.add(eventIdx);
        }
        return result;
    }

    /**
     * 매입한 금괴 개수를 플레이어 자산 및 금괴 매입 트랙( + 추가개수)에 반영
     * 플레이어별 개인 정보는 계속 브로드캐스팅 되기 때문에 redis 데이터 값만 바꿔주면됨
     *
     * @param goldBuyCount
     * @throws BaseException
     */
    @Override
    public void purchaseGold(String roomId, String userNickname, int goldBuyCount) throws BaseException, MessageException {
        Arena arena = gameRepository.findArenaByRoomId(roomId)
                .orElseThrow(() -> new BaseException(ARENA_NOT_FOUND));
        Game game = arena.getGame();
        Player player = findPlayer(arena, userNickname);

        // 금괴 매입 비용 계산
        int currentGoldPrice = game.getGoldPrice();
        int totalCost = currentGoldPrice * goldBuyCount;

        if (player.getState() == COMPLETED) {
            throw new BaseException(PLAYER_STATE_ERROR);
        }

        if (player.getCash() < totalCost) {
            throw new MessageException(roomId, userNickname, OUT_OF_CASH);
        }

        // 금괴 매입 표 변경 ( 시장에서 넣을 수 있는 랜덤 주식 넣기 )
        int[] currentMarketStocks = Arrays.stream(game.getMarketStocks())
                .mapToInt(StockInfo::getCnt)
                .toArray();
        System.out.println("현재 시장 주식들 : " + Arrays.toString(currentMarketStocks));

        List<Integer> selectableStocks = IntStream.range(1, currentMarketStocks.length)
                .filter(i -> currentMarketStocks[i] != 0)
                .boxed()
                .collect(Collectors.toList());
        System.out.println("뽑을 수 있는 0이 아닌 주식 : " + selectableStocks);

        int[] goldBuyTrack = game.getGoldBuyTrack();
        System.out.println("금괴 매수 트랙 : " + Arrays.toString(goldBuyTrack));

        int selectedStock = -1;
        for (int i = 1; i < goldBuyTrack.length; i++) {
            if (goldBuyTrack[i] == 0) {
                int randomIdx = selectableStocks.get(random.nextInt(selectableStocks.size()));
                selectedStock = randomIdx;
                System.out.println("랜덤으로 선택된 주식 종류 : " + randomIdx);
                goldBuyTrack[i] = randomIdx;
                // 선택 주식을 시장에서 제거
                currentMarketStocks[randomIdx]--;
                break;
            }
        }
        game.setGoldBuyTrack(goldBuyTrack);
        System.out.println("==========                     changed                   =========");
        System.out.println("금괴 매수 트랙 : " + Arrays.toString(goldBuyTrack));
        System.out.println("변경된 시장 주식들 : " + Arrays.toString(currentMarketStocks));

        // 시장에 반영
        StockInfo[] updatedMarketStocks = game.getMarketStocks();
        for (int i = 0; i < currentMarketStocks.length; i++) {
            updatedMarketStocks[i].setCnt(currentMarketStocks[i] + goldBuyTrack[i]);
        }
        game.setMarketStocks(updatedMarketStocks);

        // 금괴 추가 매입 수치 변경
        int currentGoldPriceIncreaseCnt = game.getGoldPriceIncreaseCnt();
        game.setGoldPriceIncreaseCnt(currentGoldPriceIncreaseCnt + goldBuyCount);

        // 자산에 금괴 개수 반영 및 금액 지불
        int currentMyCash = player.getCash();
        int currentMyGold = player.getGoldOwned();

        player.setCash(currentMyCash - currentGoldPrice * goldBuyCount);
        player.setGoldOwned(currentMyGold + goldBuyCount);
        player.setState(PlayerStatus.COMPLETED);
        player.setCarryingGolds(goldBuyCount);

        // 변경된 정보를 반영
        List<Player> players = game.getPlayers();
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getNickname().equals(userNickname)) {
                players.set(i, player);
                break;
            }
        }
        game.setPlayers(players);

        // 금괴 매입 트랙에 의한 주가 상승 체크 및 반영
        // 1. 넣은 주식과 같은 종류의 주식이 딱 3개
        // 2. 뽑은 주식이 시장에서 해당 종류 마지막 토큰인 경우

        if (isStockNumThree(goldBuyTrack, selectedStock) || isStockMarketEmpty(currentMarketStocks, selectedStock)) {
            updatedMarketStocks[selectedStock].increaseState();
            System.out.println("주가상승!");
        }

        // 금괴 매입 트랙에 의한 주가 변동 체크 및 반영 - 꽉 찼을 때
        if (isStockFluctuationAble(goldBuyTrack)) {
            game.setGoldBuyTrack(new int[]{0, 0, 0, 0, 0, 0});
            game.setRoundStatus(STOCK_FLUCTUATION); // TODO 주가변동 메서드 스케줄러에 넣기
            System.out.println("주가변동!!");
        }

        arena.setGame(game);
        gameRepository.saveArena(roomId, arena);
        System.out.println("==================================================================");
    }

    private boolean isStockNumThree(int[] goldBuyTrack, int selectedStock) {
        int checkStockCnt = 0;
        for (int i = 1; i < goldBuyTrack.length; i++) {
            if (goldBuyTrack[i] == selectedStock) {
                checkStockCnt++;
            }
        }
        return checkStockCnt == 3;
    }

    private boolean isStockMarketEmpty(int[] currentMarketStocks, int selectedStock) {
        return currentMarketStocks[selectedStock] == 0;
    }

    private boolean isStockFluctuationAble(int[] goldBuyTrack) {
        for (int i = 1; i < goldBuyTrack.length; i++) {
            if (goldBuyTrack[i] == 0) {
                return false;
            }
        }
        return true;
    }


    /**
     * 주가 변동 가능 여부 체크 ( 주가 매수 트랙, 주가 매도 트랙, 금괴 매입 트랙) -> 주가 변동 로직 실행
     *
     * @param roomId
     * @return
     * @throws BaseException
     */
    @Override
    public boolean isStockFluctuationAble(String roomId) throws BaseException {
        return false;
    }

    // 대출

    /**
     * [preLoan] 대출 가능 여부 판단 후, 대출 금액 범위 리턴
     *
     * @param roomId
     * @param sender
     * @return 대출 금액 범위
     * @throws BaseException 1. 이미 대출을 받은 적이 있는 경우 2. 유효하지 않은 주가수준인 경우
     */
    public int preLoan(String roomId, String sender) throws BaseException, MessageException {

        // 입력값 오류
        validateRequest(roomId, sender);

        Arena arena = gameRepository.findArenaByRoomId(roomId).orElseThrow(() -> new BaseException(ARENA_NOT_FOUND));
        Player player = findPlayer(arena, sender);

        // 이미 대출을 받은 적이 있는 경우
        if (player.getHasLoan() == 1) {
            throw new MessageException(roomId, sender, LOAN_ALREADY_TAKEN);
        }

        int stockPriceLevel = arena.getGame().getCurrentStockPriceLevel();

        // 주가 수준에 따른 가능 대출 범위 리턴
        // 유효하지 않은 주가수준일 경우
        if (stockPriceLevel < 0 || stockPriceLevel > 9) {
            throw new BaseException(INVALID_STOCK_LEVEL);
        } else if (stockPriceLevel <= 2) {
            return 0;
        } else if (stockPriceLevel <= 5) {
            return 1;
        } else {
            return 2;
        }
    }

    /**
     * [takeLoan] 대출 후 자산반영, 메세지 전송
     *
     * @param roomId
     * @param sender
     * @throws BaseException 요청 금액이 대출 한도를 넘어가는 경우
     */
    @Override
    public void takeLoan(String roomId, String sender, int amount) throws BaseException, MessageException {

        validateRequest(roomId, sender);
        int range = preLoan(roomId, sender);

        // 대출금을 자산에 반영
        Arena arena = gameRepository.findArenaByRoomId(roomId).orElseThrow(() -> new BaseException(ARENA_NOT_FOUND));
        Player player = findPlayer(arena, sender);

        // 요청 금액이 대출 한도를 이내인지 검사
        if (amount < LOAN_RANGE[range][0] || LOAN_RANGE[range][1] < amount) {
            throw new MessageException(roomId, sender, AMOUNT_OUT_OF_RANGE);
        }

        int interest = (int) (amount * (arena.getGame().getCurrentInterestRate() / 100.0));

        player.setHasLoan(1);
        player.setLoanPrincipal(amount);
        player.setLoanInterest(interest);
        player.setTotalDebt(amount);
        player.setCash(player.getCash() + amount);

        gameRepository.saveArena(roomId, arena);
    }

    // 상환

    /**
     * [repayLoan] 상환 후 자산 반영, 메세지 전송
     *
     * @throws BaseException 상환 금액이 유효하지 않은 값일 때
     */
    @Override
    public void repayLoan(String roomId, String sender, int amount) throws BaseException, MessageException {

        validateRequest(roomId, sender);

        Arena arena = gameRepository.findArenaByRoomId(roomId).orElseThrow(() -> new BaseException(ARENA_NOT_FOUND));
        Player player = findPlayer(arena, sender);

        int totalDebt = player.getTotalDebt();
        int cash = player.getCash();

        if (amount > totalDebt) {
            throw new MessageException(roomId, sender, AMOUNT_EXCEED_DEBT);
        }
        if (amount > cash) {
            throw new MessageException(roomId, sender, AMOUNT_EXCEED_CASH);
        }

        // 상환 후 자산에 반영(갚아야 할 금액 차감, 현금 차감)
        player.repayLoan(amount);

        gameRepository.saveArena(roomId, arena);
    }


    // 주식 매도
    @Override
    public void sellStock(String roomId, String sender, int[] stocksToSell) throws BaseException {

        validateRequest(roomId, sender);
        Arena arena = gameRepository.findArenaByRoomId(roomId).orElseThrow(() -> new BaseException(ARENA_NOT_FOUND));

        Game game = arena.getGame();
        int currentStockPriceLevel = game.getCurrentStockPriceLevel();
        StockInfo[] marketStocks = game.getMarketStocks();
        int[] stockSellTrack = game.getStockSellTrack();
        Player player = findPlayer(arena, sender);
        int[] ownedStocks = player.getStock();

        if (player.getState() == COMPLETED) {
            throw new BaseException(PLAYER_STATE_ERROR);
        }

        // 1. stocks 유효성 검사 (각 숫자가 0 이상/합산한 개수가 0 초과 주가 수준 거래 가능 토큰 개수 이하)
        validateStocks(stocksToSell, currentStockPriceLevel);

        // stocksToSell이 내가 보유한 주식의 개수보다 작은지 판별
        for (int i = 1; i < 6; i++) {
            if (stocksToSell[i] > ownedStocks[i]) {
                throw new BaseException(INVALID_SELL_STOCKS);
            }
        }

        // 2. 주식 매도 가격 계산
        int salePrice = 0;  // 주식 매도 대금
        int stockPrice;
        for (int i = 1; i < 6; i++) {
            stockPrice = stockState.getStockStandard()[marketStocks[i].getState()[0]][marketStocks[i].getState()[1]].getPrice();
            salePrice += stockPrice * stocksToSell[i];
            ownedStocks[i] -= stocksToSell[i];
            marketStocks[i].addCnt(stocksToSell[i]);
        }

        // 3. 개인 현금에 매도 가격 적용하고 거래 행위 완료로 변경
        player.addCash(salePrice);
        player.setState(PlayerStatus.COMPLETED);

        // 4. 매도 트랙에서 주식시장으로 토큰 옮기고 주가 하락
        moveStockFromSellTrackAndCheckDecrease(marketStocks, stockSellTrack);

        // 5. 남은 주식토큰이 5개면 주가 변동 -> 주식 매도트랙 세팅
        int leftStocks = 0;
        for (int i = 1; i < 6; i++) {
            leftStocks += stockSellTrack[i];
        }
        if (leftStocks == 5) {
            game.setRoundStatus(STOCK_FLUCTUATION);

            // 6. 매도트랙 세팅
            for (int i = 1; i < 6; i++) {
                game.getStockTokensPocket()[i] += stockSellTrack[i];
            }
            game.setStockSellTrack(new int[]{2, 2, 2, 2, 2, 2});
        }

        gameRepository.saveArena(roomId, arena);
    }

    /**
     * 매도트랙에서 주식시장으로 주식 토큰 하나 옮기고 주가 하락 (주식시장으로 옮길 토큰이 없을 시 수행하지 않음)
     *
     * @param marketStocks
     * @param stockSellTrack
     * @throws BaseException : 하락한 주가의 좌표가 유효하지 않은 주가 기준표의 좌표일 경우
     */
    public void moveStockFromSellTrackAndCheckDecrease(StockInfo[] marketStocks, int[] stockSellTrack) throws BaseException {
        List<Integer> availableStocks = new ArrayList<>(5);
        for (int i = 1; i < 6; i++) {
            if (stockSellTrack[i] > 0) {
                availableStocks.add(i);
            }
        }

        if (!availableStocks.isEmpty()) {
            int randomStockIndex = availableStocks.get(random.nextInt(availableStocks.size()));
            System.out.println("!!!!!!!!randomIndex!!!!!!!! : " + randomStockIndex);
            stockSellTrack[randomStockIndex] -= 1;
            marketStocks[randomStockIndex].addCnt(1);
            marketStocks[randomStockIndex].decreaseState();
        }
    }


    // 주식 매수

    // 주가 변동
    public void changeStockPrice(Game game) throws BaseException {
        int stockPriceLevel = game.getCurrentStockPriceLevel();

        int[] stockTokensPocket = game.getStockTokensPocket();

        // 1. 현재 주가 수준에 해당하는 주식 토큰의 개수를 뽑기
        int[] selectedStockCnts = new int[6];

        // 1-1. 인덱스 선택
        List<Integer> validIndices = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            if (stockTokensPocket[i] > 0) {
                validIndices.add(i);
            }
        }

        // 1-2. 주어진 tokensCnt 만큼 랜덤으로 인덱스 선택해서 값 줄이기
        int tokensCnt = stockState.getStockLevelCards()[stockPriceLevel][1];
        for (int i = 0; i < tokensCnt; i++) {
            if (validIndices.isEmpty()) {
                throw new BaseException(INSUFFICIENT_STOCK);
            }

            int randomIndex = validIndices.get(random.nextInt(validIndices.size()));

            stockTokensPocket[randomIndex] -= 1;
            selectedStockCnts[randomIndex] += 1;

            if (stockTokensPocket[randomIndex] == 0) {
                validIndices.remove((Integer) randomIndex);
            }
        }

        // 2. 금 시세 조정
        int blackTokenCnt = selectedStockCnts[0];
        if (blackTokenCnt > 0) {
            // 2-1. 검은색 주식 토큰 개수만큼 금 마커를 금 시세 트랙에서 위쪽으로 한 칸씩 이동
            game.addGoldPrice(blackTokenCnt);
            // 2-2. 매입 금괴 표시 트랙에서 금 마커가 마지막으로 지나가거나, 도달한 3의 배수 칸 오른쪽 아래에 표시된 숫자만큼 위쪽으로 이동.
            game.addGoldPrice(game.getGoldPriceIncreaseCnt() / 3);
        }
        // 2-3. 매입 금괴 표시 트랙에 놓인 금 마커를 0으로 이동
        game.setGoldPriceIncreaseCnt(0);

        // 3. 주가 조정
        StockInfo[] marketStocks = game.getMarketStocks();
        if (0 <= blackTokenCnt && blackTokenCnt <= 12) {

            // 3-1. 검은색 토큰 1개: 검은색 토큰을 다시 주머니에 넣고, 나머지 주식 토큰들로 아래 수행
            if (blackTokenCnt == 1) {
                stockTokensPocket[0] += 1;
                selectedStockCnts[0] = 0;
            }

            // 3-2. 뽑은 주식 토큰 中, 각 색깔의 주가 토큰 개수가 표시된 위치로 이동(*주가 조정 참조표* 참고)
            for (int i = 1; i < 6; i++) {
                int stockCntDiff = selectedStockCnts[i] - selectedStockCnts[0];
                if (stockCntDiff < -6) {
                    throw new BaseException(INVALID_BLACK_TOKEN);
                }
                int[] stockPriceState = marketStocks[i].getState();

                if (0 <= stockCntDiff && stockCntDiff < 7) {
                    stockPriceState[0] += stockState.getStockDr()[stockCntDiff];
                    stockPriceState[1] += stockState.getStockDc()[stockCntDiff];
                }
                // 3-3. 7개 이상 뽑았다면, 참조표에 표시된 6까지 이동 후 -> 초과한 숫자만큼 위로 한 칸씩 이동
                else if (7 <= stockCntDiff && stockCntDiff <= 12) {
                    stockPriceState[0] += stockState.getStockDr()[6];
                    stockPriceState[1] += stockState.getStockDc()[6];
                    for (int j = 0; j < stockCntDiff - 6; j++) {
                        if (stockPriceState[0] == 0) {
                            break;
                        }
                        stockPriceState[0] -= 1;
                    }
                } else {
                    throw new BaseException(EXCEEDS_DIFF_RANGE);
                }

                // 4. 주식 토큰 정리: 주머니에서 뽑은 색깔 주식 토큰을 일치하는 색깔의 주식시장에 놓기
                marketStocks[i].addCnt(selectedStockCnts[i]);

                // 5. 주가 상승: 여전히 주식 시장에 주식 토큰이 없는 색깔은 주가를 위쪽으로 한 칸 이동
                if (marketStocks[i].getCnt() == 0) stockPriceState[0] -= 1;

                // 6. 주가 수준 변동 조건 확인 후, 필요 시 주가 수준 변동
                int newLevel = stockState.getStockStandard()[stockPriceState[0]][stockPriceState[1]].getLevel();
                // 새로운 주가수준이 상위영역에 처음 진입했는지
                if (stockPriceLevel < newLevel) {
                    game.setCurrentStockPriceLevel(newLevel);
                    stockPriceLevel = newLevel;
                }
            }
        } else {
            throw new BaseException(INVALID_BLACK_TOKEN);
        }
    }

    // 플레이어 이동
    @Override
    public void movePlayer(StompPayload<PlayerMoveRequest> payload) throws BaseException {
        String roomId = payload.getRoomId();

        Arena arena = gameRepository.findArenaByRoomId(roomId).orElseThrow(() -> new BaseException(ARENA_NOT_FOUND));

        synchronized (arena) {
            Player player = findPlayer(arena, payload.getSender());
            PlayerMoveRequest playerMoveRequest = payload.getData();
            player.setDirection(playerMoveRequest.direction());
            player.setPosition(playerMoveRequest.position());
            player.setActionToggle(playerMoveRequest.actionToggle());

            gameRepository.saveArena(roomId, arena);
        }
    }

    @Override
    public void buyStock(StompPayload<StockRequest> payload) throws BaseException, MessageException {
        String roomId = payload.getRoomId();
        String playerNickname = payload.getSender();
        int[] stocksToBuy = payload.getData().stocks();

        Arena arena = gameRepository.findArenaByRoomId(roomId).orElseThrow(() -> new BaseException(ARENA_NOT_FOUND));

        synchronized (arena) {
            Player player = findPlayer(arena, playerNickname);
            Game game = arena.getGame();

            int stockPriceLevel = game.getCurrentStockPriceLevel();
            StockInfo[] marketStocks = game.getMarketStocks();
            int[] stockBuyTrack = game.getStockBuyTrack();

            int totalCost = calculateTotalCost(stocksToBuy, marketStocks);

            validateStocks(stocksToBuy, stockPriceLevel);
            validateStockAvailability(stocksToBuy, marketStocks, roomId, playerNickname);

            if (player.getCash() < totalCost) {
                throw new MessageException(roomId, playerNickname, INSUFFICIENT_CASH);
            }
            player.setCash(player.getCash() - totalCost);

            updatePlayerStocks(stocksToBuy, player);
            updateStockMarket(stocksToBuy, marketStocks);

            boolean hasStockPriceIncreased = updateSellTrackAndCheckIncrease(marketStocks, stockBuyTrack);

            if (!hasStockPriceIncreased) {
                checkAndApplyStockPriceIncrease(stockBuyTrack, marketStocks);
            }

            checkAndApplyStockPriceChange(stockBuyTrack, game, stockPriceLevel);

            gameRepository.saveArena(roomId, arena);
        }
    }

    private int calculateTotalCost(int[] stocksToBuy, StockInfo[] marketStocks) {
        int totalCost = 0;
        for (int i = 1; i < 6; i++) {
            if (stocksToBuy[i] > 0) {
                int[] priceLocation = marketStocks[i].getState();
                int price = stockState.getStockStandard()[priceLocation[0]][priceLocation[1]].getPrice();
                totalCost += price * stocksToBuy[i];
            }
        }
        return totalCost;
    }

    private void validateStockAvailability(int[] stocksToBuy, StockInfo[] marketStocks, String roomId, String playerNickname) throws MessageException {
        for (int i = 1; i < 6; i++) {
            if (stocksToBuy[i] > 0 && marketStocks[i].getCnt() < stocksToBuy[i]) {
                throw new MessageException(roomId, playerNickname, STOCK_NOT_AVAILABLE);
            }
        }
    }

    private void updatePlayerStocks(int[] stocksToBuy, Player player) {
        for (int i = 1; i < 6; i++) {
            if (stocksToBuy[i] > 0) {
                player.getCarryingStocks()[i] += stocksToBuy[i];
            }
        }
    }

    private void updateStockMarket(int[] stocksToBuy, StockInfo[] marketStocks) {
        for (int i = 1; i < 6; i++) {
            if (stocksToBuy[i] > 0) {
                marketStocks[i].setCnt(marketStocks[i].getCnt() - stocksToBuy[i]);
            }
        }
    }

    private boolean updateSellTrackAndCheckIncrease(StockInfo[] marketStocks, int[] stockBuyTrack) throws BaseException {
        List<Integer> availableStocks = new ArrayList<>(5);
        for (int i = 1; i < 6; i++) {
            if (marketStocks[i].getCnt() > 0) {
                availableStocks.add(i);
            }
        }

        if (!availableStocks.isEmpty()) {
            int randomStockIndex = availableStocks.get(random.nextInt(availableStocks.size()));
            stockBuyTrack[randomStockIndex]++;

            marketStocks[randomStockIndex].setCnt(marketStocks[randomStockIndex].getCnt() - 1);
            if (marketStocks[randomStockIndex].getCnt() == 0) {
                marketStocks[randomStockIndex].increaseState();
                return true;
            }
        }
        return false;
    }

    private void checkAndApplyStockPriceChange(int[] stockBuyTrack, Game game, int stockPriceLevel) throws BaseException {
        int totalStockInTrack = 0;
        for (int count : stockBuyTrack) {
            totalStockInTrack += count;
        }

        if (totalStockInTrack == 5) {
            changeStockPrice(game);
        }
    }

    private void checkAndApplyStockPriceIncrease(int[] stockBuyTrack, StockInfo[] marketStocks) throws BaseException {
        for (int i = 1; i < 6; i++) {
            if (stockBuyTrack[i] == 3) {
                marketStocks[i].increaseState();
                break;
            }
        }
    }

    @Override
    public void setStockPriceChangeInfo(Game game, int round, int remainTime) {
        int x_value = ((round - 1) * 120 + (120 - remainTime)) / 20;

        StockInfo[] marketStocks = game.getMarketStocks();
        for (int i = 1; i < 6; i++) {
            int r = marketStocks[i].getState()[0];
            int c = marketStocks[i].getState()[1];
            game.getStockPriceChangeInfo()[i][x_value] = stockState.getStockStandard()[r][c].getPrice();
        }
    }

    private Player findPlayer(Arena arena, String nickname) throws BaseException {
        return arena.getGame().getPlayers().stream()
                .filter(p -> p.getNickname().equals(nickname))
                .findFirst()
                .orElseThrow(() -> new BaseException(PLAYER_NOT_FOUND));
    }

    /**
     * 요청의 입력유효성 검사
     *
     * @param sender 요청을 보낸 사용자의 닉네임
     * @throws BaseException roomId나 sender가 null이거나 비어있을 경우 발생
     */
    private void validateRequest(String roomId, String sender) throws BaseException {
        if (roomId == null || roomId.isEmpty() || sender == null || sender.isEmpty()) {
            throw new BaseException(REQUEST_ERROR);
        }
    }

    /**
     * 주식 매수/매도 시 거래 요청한 주식의 검사
     *
     * @param stocksToSell    : 플레이어가 파려고 하는 주식들
     * @param stockPriceLevel : 주가 수준
     * @throws BaseException : 아래 두 조건을 만족하지 않는 경우
     *                       - 각 숫자가 0 미만인 동시에 거래 주식 개수가 0 초과 거래가능토큰개수(주가수준 기준) 이하
     *                       - 주식 종류별 내가 보유한 주식 개수 이하
     */
    private void validateStocks(int[] stocksToSell, int stockPriceLevel) throws BaseException {
        // 각 숫자가 0 이상 && 합산한 개수가 0 초과 주가 수준 거래 가능 토큰 개수 이하
        int stockCnt = 0;
        for (int i = 1; i < 6; i++) {
            if (stocksToSell[i] < 0) {
                throw new BaseException(INVALID_SELL_STOCKS);
            }
            stockCnt += stocksToSell[i];
        }

        if (stockCnt > stockState.getStockLevelCards()[stockPriceLevel][0] || stockCnt <= 0) {
            throw new BaseException(IMPOSSIBLE_STOCK_CNT);
        }
    }
}
