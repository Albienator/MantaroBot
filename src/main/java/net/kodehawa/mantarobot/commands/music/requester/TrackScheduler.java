/*
 * Copyright (C) 2016-2018 David Alejandro Rubio Escares / Kodehawa
 *
 * Mantaro is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * Mantaro is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro.  If not, see http://www.gnu.org/licenses/
 */

package net.kodehawa.mantarobot.commands.music.requester;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import lavalink.client.io.Link;
import lavalink.client.player.IPlayer;
import lavalink.client.player.LavalinkPlayer;
import lavalink.client.player.event.PlayerEventListenerAdapter;
import lombok.Getter;
import lombok.Setter;
import net.dv8tion.jda.core.MessageBuilder;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.managers.AudioManager;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.music.utils.AudioUtils;
import net.kodehawa.mantarobot.core.shard.MantaroShard;
import net.kodehawa.mantarobot.data.I18n;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class TrackScheduler extends PlayerEventListenerAdapter {
    @Getter
    private final IPlayer audioPlayer;
    private final String guildId;
    @Getter
    private final ConcurrentLinkedDeque<AudioTrack> queue;
    @Getter
    private final List<String> voteSkips;
    @Getter
    private final List<String> voteStop;
    private long lastMessageSentAt;
    private long lastErrorSentAt;
    @Getter
    private AudioTrack previousTrack, currentTrack;
    @Getter
    @Setter
    private Repeat repeatMode;
    @Setter
    private long requestedChannel;
    @Getter
    private final I18n language;
    @Getter
    private final Link guildLavaLink;
    @Getter
    private final LavalinkPlayer guildPlayer;

    public TrackScheduler(IPlayer player, String guildId) {
        this.audioPlayer = player;
        this.queue = new ConcurrentLinkedDeque<>();
        this.guildId = guildId;
        this.voteSkips = new ArrayList<>();
        this.voteStop = new ArrayList<>();

        //Only take guild language settings into consideration for announcement messages.
        this.language = I18n.of(guildId);
        this.guildLavaLink = MantaroBot.getInstance().getShardForGuild(guildId).getLavalink().getLink(guildId);
        guildPlayer = guildLavaLink.getPlayer();
    }

    public void queue(AudioTrack track, boolean addFirst) {
        if(guildPlayer.getPlayingTrack() != null) {
            if(addFirst)
                queue.addFirst(track);
            else
                queue.offer(track);
        } else {
            guildPlayer.playTrack(track);
            currentTrack = track;
        }
    }

    public void queue(AudioTrack track) {
        queue(track, false);
    }

    public void nextTrack(boolean force, boolean skip) {
        getVoteSkips().clear();
        if(repeatMode == Repeat.SONG && currentTrack != null && !force) {
            queue(currentTrack.makeClone());
        } else {
            if(currentTrack != null)
                previousTrack = currentTrack;
            currentTrack = queue.poll();

            //todo handle noInterrupt param on lavalink?
            guildPlayer.playTrack(currentTrack);
            //audioPlayer.startTrack(currentTrack, !force);

            if(skip)
                onTrackStart();
            if(repeatMode == Repeat.QUEUE)
                queue(previousTrack.makeClone());
        }
    }

    private void onTrackStart() {
        if(currentTrack == null) {
            onStop();
            return;
        }

        if(MantaroData.db().getGuild(guildId).getData().isMusicAnnounce() && requestedChannel != 0 && getRequestedChannelParsed() != null) {
            VoiceChannel voiceChannel = getRequestedChannelParsed().getGuild().getSelfMember().getVoiceState().getChannel();

            //What kind of massive meme is this?
            //It's called mantaro
            if(voiceChannel == null) return;

            if(getRequestedChannelParsed().canTalk()) {
                AudioTrackInfo information = currentTrack.getInfo();
                String title = information.title;
                long trackLength = information.length;

                User user = null;
                if(getCurrentTrack().getUserData() != null) {
                    user = MantaroBot.getInstance().getUserById(String.valueOf(getCurrentTrack().getUserData()));
                }
                //Avoid massive spam of "now playing..." when repeating songs.
                if(lastMessageSentAt == 0 || lastMessageSentAt + 10000 < System.currentTimeMillis()) {
                    getRequestedChannelParsed().sendMessage(
                            new MessageBuilder().append(String.format(language.get("commands.music_general.np_message"),
                                    "\uD83D\uDCE3", title, AudioUtils.getLength(trackLength), voiceChannel.getName(), user != null ?
                                            String.format(language.get("general.requested_by"), String.format("**%s#%s**", user.getName(), user.getDiscriminator())) : ""))
                                    .stripMentions(getGuild(), Message.MentionType.EVERYONE, Message.MentionType.HERE)
                                    .build()
                    ).queue(message -> {
                        lastMessageSentAt = System.currentTimeMillis();
                        message.delete().queueAfter(90, TimeUnit.SECONDS);
                    });
                }
            }
        }
    }

    @Override
    public void onTrackEnd(IPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if(endReason.mayStartNext) {
            nextTrack(false, false);
            onTrackStart();
        }
    }

    @Override
    public void onTrackException(IPlayer player, AudioTrack track, Exception exception) {
        if(getRequestedChannelParsed() != null && getRequestedChannelParsed().canTalk()) {
            //Avoid massive spam of when song error in mass.
            if(lastErrorSentAt == 0 || lastErrorSentAt + 10000 < System.currentTimeMillis()) {
                getRequestedChannelParsed().sendMessageFormat(
                        language.get("commands.music_general.track_error"), EmoteReference.SAD
                ).queue(success -> lastErrorSentAt = System.currentTimeMillis());
            }
        }
    }

    public Guild getGuild() {
        return MantaroBot.getInstance().getGuildById(guildId);
    }

    public int getRequiredVotes() {
        int listeners = (int) getGuild().getAudioManager().getConnectedChannel().getMembers().stream().filter(m -> !m.getUser().isBot() && !m.getVoiceState().isDeafened())
                .count();

        return (int) Math.ceil(listeners * .55);
    }

    public void shuffle() {
        List<AudioTrack> tempList = new ArrayList<>(getQueue());
        Collections.shuffle(tempList);

        queue.clear();
        queue.addAll(tempList);
    }

    public MantaroShard getShard() {
        return MantaroBot.getInstance().getShard(getGuild().getJDA().getShardInfo().getShardId());
    }

    public TextChannel getRequestedChannelParsed() {
        if(requestedChannel == 0) return null;
        return MantaroBot.getInstance().getTextChannelById(requestedChannel);
    }

    public void stop() {
        queue.clear();
        onStop();
    }

    public void getQueueAsList(Consumer<List<AudioTrack>> list) {
        List<AudioTrack> tempList = new ArrayList<>(getQueue());
        list.accept(tempList);
        queue.clear();
        queue.addAll(tempList);
    }

    private void onStop() {
        getVoteStop().clear();
        getVoteSkips().clear();

        Guild g = getGuild();
        if(g == null) return;
        AudioManager m = g.getAudioManager();
        if(m == null) return;

        boolean premium = MantaroData.db().getGuild(g).isPremium();

        try {
            TextChannel ch = getRequestedChannelParsed();
            if(ch != null && ch.canTalk()) {
                ch.sendMessageFormat(
                        language.get("commands.music_general.queue_finished"),
                        EmoteReference.MEGA, premium ? "" : String.format(language.get("commands.music_general.premium_beg"), EmoteReference.HEART)
                ).queue(message -> message.delete().queueAfter(30, TimeUnit.SECONDS));
            }
        } catch(Exception ignored) { }

        requestedChannel = 0;
        guildLavaLink.disconnect();
    }

    public enum Repeat {
        SONG, QUEUE
    }
}
