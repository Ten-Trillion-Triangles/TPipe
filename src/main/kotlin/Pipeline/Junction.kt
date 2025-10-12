package com.TTT.Pipeline

/**
 * The Junction is a class that allows a democratic voting and discussion pattern between multiple agents that are
 * specialists at a given task and have a specific role or point of view to focus on. A moderator pipeline decides
 * if a decision has been made and a solid plan has been devised either based on clearing a minimum set of user defined
 * votes, or based on making an arbitrary decision after a set number of rounds.
 *
 * Discussions will iterate through a defined number of rounds before the moderator intervenes. The following strategies
 * can be employed:
 *
 * - Each agent gives it's opinion at once. Then at the start of the next round each agent reads the opinion of each
 * other agent and iterates again. This repeats until the moderator is ready to intervene.
 * - Each agent gives an initial suggestion. Then at the second round and beyond chooses which agent to converse with.
 * This repeats until the moderator intervenes.
 * - Each agent is allowed to speak in a round-robin pattern starting from index 0 to last index. The moderator
 * intervenes after a set number of rounds.
 *
 * Once a decision has been made a planning pipeline can be called to plan out what to do, and act on the decision
 * that was made.
 */
class Junction
{
}