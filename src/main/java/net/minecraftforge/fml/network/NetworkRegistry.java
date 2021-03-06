/*
 * Minecraft Forge
 * Copyright (c) 2016-2019.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fml.network;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.network.event.EventNetworkChannel;
import net.minecraftforge.fml.network.simple.SimpleChannel;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The network registry. Tracks channels on behalf of mods.
 */
public class NetworkRegistry
{
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Marker NETREGISTRY = MarkerManager.getMarker("NETREGISTRY");

    private static Map<ResourceLocation, NetworkInstance> instances = new HashMap<>();

    /**
     * Special value for clientAcceptedVersions and serverAcceptedVersions predicates indicating the other side lacks
     * this channel.
     */
    @SuppressWarnings("RedundantStringConstructorCall")
    public static String ABSENT = new String("ABSENT \uD83E\uDD14");

    public static List<String> getNonVanillaNetworkMods()
    {
        return instances.keySet().stream().map(Object::toString).collect(Collectors.toList());
    }

    static boolean acceptsVanillaConnections() {
        return instances.isEmpty();
    }


    /**
     * Create a new {@link SimpleChannel}.
     *
     * @param name The registry name for this channel. Must be unique
     * @param networkProtocolVersion The network protocol version string that will be offered to the remote side {@link ChannelBuilder#networkProtocolVersion(Supplier)}
     * @param clientAcceptedVersions Called on the client with the networkProtocolVersion string from the server {@link ChannelBuilder#clientAcceptedVersions(Predicate)}
     * @param serverAcceptedVersions Called on the server with the networkProtocolVersion string from the client {@link ChannelBuilder#serverAcceptedVersions(Predicate)}
     * @return A new {@link SimpleChannel}
     *
     * @see ChannelBuilder#newSimpleChannel(ResourceLocation, Supplier, Predicate, Predicate)
     */
    public static SimpleChannel newSimpleChannel(final ResourceLocation name, Supplier<String> networkProtocolVersion, Predicate<String> clientAcceptedVersions, Predicate<String> serverAcceptedVersions) {
        return new SimpleChannel(createInstance(name, networkProtocolVersion, clientAcceptedVersions, serverAcceptedVersions));
    }

    /**
     * Create a new {@link EventNetworkChannel}.
     *
     * @param name The registry name for this channel. Must be unique
     * @param networkProtocolVersion The network protocol version string that will be offered to the remote side {@link ChannelBuilder#networkProtocolVersion(Supplier)}
     * @param clientAcceptedVersions Called on the client with the networkProtocolVersion string from the server {@link ChannelBuilder#clientAcceptedVersions(Predicate)}
     * @param serverAcceptedVersions Called on the server with the networkProtocolVersion string from the client {@link ChannelBuilder#serverAcceptedVersions(Predicate)}

     * @return A new {@link EventNetworkChannel}
     *
     * @see ChannelBuilder#newEventChannel(ResourceLocation, Supplier, Predicate, Predicate)
     */
    public static EventNetworkChannel newEventChannel(final ResourceLocation name, Supplier<String> networkProtocolVersion, Predicate<String> clientAcceptedVersions, Predicate<String> serverAcceptedVersions) {
        return new EventNetworkChannel(createInstance(name, networkProtocolVersion, clientAcceptedVersions, serverAcceptedVersions));
    }


    /**
     * Creates the internal {@link NetworkInstance} that tracks the channel data.
     * @param name registry name
     * @param networkProtocolVersion The protocol version string
     * @param clientAcceptedVersions The client accepted predicate
     * @param serverAcceptedVersions The server accepted predicate
     * @return The {@link NetworkInstance}
     * @throws IllegalArgumentException if the name already exists
     */
    private static NetworkInstance createInstance(ResourceLocation name, Supplier<String> networkProtocolVersion, Predicate<String> clientAcceptedVersions, Predicate<String> serverAcceptedVersions)
    {
        final NetworkInstance networkInstance = new NetworkInstance(name, networkProtocolVersion, clientAcceptedVersions, serverAcceptedVersions);
        if (instances.containsKey(name)) {
            LOGGER.error(NETREGISTRY, "NetworkDirection channel {} already registered.", name);
            throw new IllegalArgumentException("NetworkDirection Channel {"+ name +"} already registered");
        }
        instances.put(name, networkInstance);
        return networkInstance;
    }

    /**
     * Find the {@link NetworkInstance}, if possible
     *
     * @param resourceLocation The network instance to lookup
     * @return The {@link Optional} {@link NetworkInstance}
     */
    static Optional<NetworkInstance> findTarget(ResourceLocation resourceLocation)
    {
        return Optional.ofNullable(instances.get(resourceLocation));
    }

    /**
     * Construct the NBT representation of the channel list, for use during login handshaking
     *
     * @see FMLHandshakeMessages.S2CModList
     * @see FMLHandshakeMessages.C2SModListReply
     *
     * @return An nbt tag list
     */
    static NBTTagList buildChannelVersions() {
        return instances.entrySet().stream().map(e-> {
            final NBTTagCompound tag = new NBTTagCompound();
            tag.setString("name", e.getKey().toString());
            tag.setString("version", e.getValue().getNetworkProtocolVersion());
            return tag;
        }).collect(Collectors.toCollection(NBTTagList::new));
    }

    /**
     * Validate the channels from the server on the client. Tests the client predicates against the server
     * supplied network protocol version.
     *
     * @param channels An @{@link NBTTagList} of name->version pairs for testing
     * @return true if all channels accept themselves
     */
    static boolean validateClientChannels(final NBTTagList channels) {
        return validateChannels(channels, "server", NetworkInstance::tryServerVersionOnClient);
    }

    /**
     * Validate the channels from the client on the server. Tests the server predicates against the client
     * supplied network protocol version.
     * @param channels An @{@link NBTTagList} of name->version pairs for testing
     * @return true if all channels accept themselves
     */
    static boolean validateServerChannels(final NBTTagList channels) {
        return validateChannels(channels, "client", NetworkInstance::tryClientVersionOnServer);
    }

    /**
     * Tests if the nbt list matches with the supplied predicate tester
     *
     * @param channels An @{@link NBTTagList} of name->version pairs for testing
     * @param originName A label for use in logging (where the version pairs came from)
     * @param testFunction The test function to use for testing
     * @return true if all channels accept themselves
     */
    private static boolean validateChannels(final NBTTagList channels, final String originName, BiFunction<NetworkInstance, String, Boolean> testFunction) {
        Map<ResourceLocation, String> incoming = channels.stream().map(NBTTagCompound.class::cast).collect(Collectors.toMap(tag->new ResourceLocation(tag.getString("name")),tag->tag.getString("version")));

        final List<Pair<ResourceLocation, Boolean>> results = instances.values().stream().
                map(ni -> {
                    final String incomingVersion = incoming.getOrDefault(ni.getChannelName(), ABSENT);
                    final boolean test = testFunction.apply(ni, incomingVersion);
                    LOGGER.debug(NETREGISTRY, "Channel '{}' : Version test of '{}' from {} : {}", ni.getChannelName(), incomingVersion, originName, test ? "ACCEPTED" : "REJECTED");
                    return Pair.of(ni.getChannelName(), test);
                }).filter(p->!p.getRight()).collect(Collectors.toList());

        if (!results.isEmpty()) {
            LOGGER.error(NETREGISTRY, "Channels [{}] rejected their {} side version number",
                    results.stream().map(Pair::getLeft).map(Object::toString).collect(Collectors.joining(",")),
                    originName);
            return false;
        }
        LOGGER.debug(NETREGISTRY, "Accepting channel list from {}", originName);
        return true;
    }

    /**
     * Retrieve the {@link LoginPayload} list for dispatch during {@link FMLHandshakeHandler#tickLogin(NetworkManager)} handling.
     * Dispatches {@link net.minecraftforge.fml.network.NetworkEvent.GatherLoginPayloadsEvent} to each {@link NetworkInstance}.
     *
     * @return The {@link LoginPayload} list
     * @param direction the network direction for the request - only gathers for LOGIN_TO_CLIENT
     */
    static List<LoginPayload> gatherLoginPayloads(final NetworkDirection direction) {
        if (direction!=NetworkDirection.LOGIN_TO_CLIENT) return Collections.emptyList();
        List<LoginPayload> gatheredPayloads = new ArrayList<>();
        instances.values().forEach(ni->ni.dispatchGatherLogin(gatheredPayloads));
        return gatheredPayloads;
    }

    /**
     * Tracks individual outbound messages for dispatch to clients during login handling. Gathered by dispatching
     * {@link net.minecraftforge.fml.network.NetworkEvent.GatherLoginPayloadsEvent} during early connection handling.
     */
    public static class LoginPayload {
        /**
         * The data for sending
         */
        private final PacketBuffer data;
        /**
         * A channel which will receive a {@link NetworkEvent.LoginPayloadEvent} from the {@link FMLLoginWrapper}
         */
        private final ResourceLocation channelName;

        /**
         * Some context for logging purposes
         */
        private final String messageContext;

        public LoginPayload(final PacketBuffer buffer, final ResourceLocation channelName, final String messageContext) {
            this.data = buffer;
            this.channelName = channelName;
            this.messageContext = messageContext;
        }

        public PacketBuffer getData() {
            return data;
        }

        public ResourceLocation getChannelName() {
            return channelName;
        }

        public String getMessageContext() {
            return messageContext;
        }
    }

    /**
     * Builder for constructing network channels using a builder style API.
     */
    public static class ChannelBuilder {
        private ResourceLocation channelName;
        private Supplier<String> networkProtocolVersion;
        private Predicate<String> clientAcceptedVersions;
        private Predicate<String> serverAcceptedVersions;

        /**
         * The name of the channel. Must be unique.
         * @param channelName The name of the channel
         * @return the channel builder
         */
        public static ChannelBuilder named(ResourceLocation channelName)
        {
            ChannelBuilder builder = new ChannelBuilder();
            builder.channelName = channelName;
            return builder;
        }

        /**
         * The network protocol string for this channel. This will be gathered during login and sent to
         * the remote partner, where it will be tested with against the relevant predicate.
         *
         * @see #serverAcceptedVersions(Predicate)
         * @see #clientAcceptedVersions(Predicate)
         * @param networkProtocolVersion A supplier of strings for network protocol version testing
         * @return the channel builder
         */
        public ChannelBuilder networkProtocolVersion(Supplier<String> networkProtocolVersion)
        {
            this.networkProtocolVersion = networkProtocolVersion;
            return this;
        }

        /**
         * A predicate run on the client, with the {@link #networkProtocolVersion(Supplier)} string from
         * the server, or the special value {@link NetworkRegistry#ABSENT} indicating the absence of
         * the channel on the remote side.
         * @param clientAcceptedVersions A predicate for testing
         * @return the channel builder
         */
        public ChannelBuilder clientAcceptedVersions(Predicate<String> clientAcceptedVersions)
        {
            this.clientAcceptedVersions = clientAcceptedVersions;
            return this;
        }

        /**
         * A predicate run on the server, with the {@link #networkProtocolVersion(Supplier)} string from
         * the server, or the special value {@link NetworkRegistry#ABSENT} indicating the absence of
         * the channel on the remote side.
         * @param serverAcceptedVersions A predicate for testing
         * @return the channel builder
         */
        public ChannelBuilder serverAcceptedVersions(Predicate<String> serverAcceptedVersions)
        {
            this.serverAcceptedVersions = serverAcceptedVersions;
            return this;
        }

        /**
         * Create the network instance
         * @return the {@link NetworkInstance}
         */
        private NetworkInstance createNetworkInstance() {
            return createInstance(channelName, networkProtocolVersion, clientAcceptedVersions, serverAcceptedVersions);
        }

        /**
         * Build a new {@link SimpleChannel} with this builder's configuration.
         *
         * @return A new {@link SimpleChannel}
         */
        public SimpleChannel simpleChannel() {
            return new SimpleChannel(createNetworkInstance());
        }

        /**
         * Build a new {@link EventNetworkChannel} with this builder's configuration.
         * @return A new {@link EventNetworkChannel}
         */
        public EventNetworkChannel eventNetworkChannel() {
            return new EventNetworkChannel(createNetworkInstance());
        }
    }
}
