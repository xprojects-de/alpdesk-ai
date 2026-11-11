import {
    allSoftwareOff,
    choiceToSend,
    excludedToSend,
    showsSoftwareChoice,
    storedChoice,
    toggled,
    topicsSwitchedOn,
} from "./software-choice.util";

describe("software-choice.util", () => {
    const topics = [
        {id: 1, name: "Thema A", instructions: null},
        {id: 2, name: "Thema B", instructions: "Wartung"},
    ];

    it("switches a topic off and on again", () => {
        expect(toggled([], 2)).toEqual([2]);
        expect(toggled([1, 2], 2)).toEqual([1]);
    });

    it("shows the chips only with two or more topics", () => {
        expect(showsSoftwareChoice([])).toBe(false);
        expect(showsSoftwareChoice([topics[0]])).toBe(false);
        expect(showsSoftwareChoice(topics)).toBe(true);
    });

    it("sends the switched-off topics still reached - a stale one is dropped", () => {
        expect(excludedToSend([2, 9], topics)).toEqual([2]);
        expect(excludedToSend([], topics)).toEqual([]);
    });

    it("sends nothing without chips - the conversation's stored choice stays", () => {
        expect(excludedToSend([1], [topics[0]])).toBeUndefined();
        expect(excludedToSend([1, 2], [])).toBeUndefined();
    });

    it("tells when every topic is off", () => {
        expect(allSoftwareOff([1, 2], topics)).toBe(true);
        expect(allSoftwareOff([1], topics)).toBe(false);
        expect(allSoftwareOff([1], [topics[0]])).toBe(false);
    });

    it("counts the topics switched on - a topic left out that is gone since counts nothing", () => {
        expect(topicsSwitchedOn([], topics)).toBe(2);
        expect(topicsSwitchedOn([2], topics)).toBe(1);
        expect(topicsSwitchedOn([1, 2, 7], topics)).toBe(0);
        expect(topicsSwitchedOn([7], topics)).toBe(2);
    });
});

describe("the agent's skills as the chips", () => {
    const skills = [
        {id: "skill:rechnungen", name: "Rechnungen", instructions: null},
        {id: "skill:urlaub", name: "Urlaub", instructions: null},
    ];

    it("sends the skills left out by their ids, without the prefix", () => {
        expect(choiceToSend(["skill:urlaub", "skill:alt"], skills)).toEqual({excludedSkillIds: ["urlaub"]});
        expect(choiceToSend([], skills)).toEqual({excludedSkillIds: []});
    });

    it("sends connections as numbers, and nothing while the chips are not shown", () => {
        const topics = [
            {id: 1, name: "Lager", instructions: null},
            {id: 2, name: "Buchhaltung", instructions: null},
        ];
        expect(choiceToSend([2], topics)).toEqual({excludedConnectionIds: [2]});
        expect(choiceToSend([2], [topics[0]])).toEqual({});
    });

    it("reads a conversation's stored choice as the chips' ids", () => {
        expect(storedChoice([3], ["urlaub"])).toEqual([3, "skill:urlaub"]);
        expect(storedChoice([], undefined)).toEqual([]);
    });
});
